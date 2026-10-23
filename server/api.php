<?php
// forge-worker job server, PHP version (same API as jobserver.py, for a PHP-capable web host).
// Several machines can work at once: each has its own token; games are leased in batches and a lease that expires
// goes back into the pool; a game counts once (first accepted upload wins, later ones are kept aside as duplicates).
//
// Data lives OUTSIDE the web root, in FORGE_DATA (default ../../uploads/forge relative to this file):
//   tokens.json                {"<sha256 of token>": "<worker name>"}   (tokens are never stored in clear)
//   campaigns/<name>/games.jsonl, decks/<deck>.dck, meta.json {"tweaks", "harness", "priority"}
//   campaigns/<name>/results/<worker>/<job>/   uploaded results.jsonl + logs
//   state.sqlite
// API (Authorization: Bearer <token>):
//   GET  api.php?a=job&slots=N     200 job JSON, or 204 when there is no work
//   POST api.php?a=result&job=ID   body: zip of results.jsonl + logs/   -> {"accepted", "duplicates"}
//   GET  api.php?a=withdrawn       campaigns withdrawn by the orchestrator (workers drop their unstarted games)
//   POST api.php?a=release&job=ID&harness=H   body: why (text). A job the client could not play (its harness did not
//        compile there, GitHub unreachable, ...): its games go back to the pool at once, and the failure is recorded
//        (table harness_fail). Campaigns on harness H are then not offered to this worker for $FAIL_RETRY s, or until
//        it restarts (the job request's "started" is later than the failure), so it gets other work. v9+ clients.
//   GET  api.php?a=status
// DATA/workers.json {"<worker name>": {"threads": N}} sets a machine's game-worker count (sent with each job; the
// client never exceeds its own limit of logical CPUs - 2).
// meta.json may also set "per_slot" (games per worker slot per batch), "lease_s" and "withdrawn": true.
umask(0);
$DATA = getenv('FORGE_DATA') ?: realpath(__DIR__ . '/../../uploads') . '/forge';
$LEASE = 1800;
$PER_SLOT = 25;
$MIN_CLIENT = 0;
$FAIL_RETRY = 1800;   // a harness a worker could not play is retried on it after this long (a=release)   // raise to make older workers stop and ask their owner to run "Update Forge Worker"

function out($code, $obj = null) {
    http_response_code($code);
    header('Content-Type: application/json');
    if ($obj !== null) echo json_encode($obj);
    exit;
}

$auth = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
$tok = str_starts_with($auth, 'Bearer ') ? substr($auth, 7) : '';
$tokens = json_decode(@file_get_contents("$DATA/tokens.json") ?: '{}', true);
$worker = $tok !== '' ? ($tokens[hash('sha256', $tok)] ?? null) : null;
if (!$worker) out(401, ['error' => 'bad token']);

$db = new SQLite3("$DATA/state.sqlite");
$db->busyTimeout(20000);
$db->exec("create table if not exists games (campaign text, gid text, status text default 'open', job text, worker text,
  lease_until real, done_at real, primary key (campaign, gid));
create table if not exists jobs (job text primary key, campaign text, worker text, created real, n integer, done real);
create table if not exists worker_client (worker text primary key, version integer, seen real, threads integer);
create table if not exists harness_fail (worker text, harness text, at real, why text, primary key (worker, harness));");

function campaigns($DATA, $db) {
    $out = [];
    foreach (glob("$DATA/campaigns/*/games.jsonl") as $f) {
        $d = dirname($f);
        $name = basename($d);
        $m = @json_decode(@file_get_contents("$d/meta.json"), true);
        if (!is_array($m) || empty($m['harness'])) continue;   // not complete yet
        $out[$name] = $m;
        if (!empty($out[$name]['withdrawn'])) continue;   // withdrawn: nothing more is issued
        // .registered holds the size of games.jsonl when it was last read (not its time: file times have one-second
        // resolution, and games appended in the same second as a read were never registered, so an experiment
        // waited forever for them)
        $mark = "$d/.registered";
        clearstatcache(true, $f);
        $size = filesize($f);
        if (file_exists($mark) && trim(@file_get_contents($mark)) === (string)$size) continue;
        $db->exec('begin immediate');
        $st = $db->prepare('insert or ignore into games (campaign, gid) values (?, ?)');
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $l) {
            $g = json_decode($l, true);
            if (!$g || !isset($g['gid'])) continue;   // a line still being appended
            $st->bindValue(1, $name); $st->bindValue(2, $g['gid']); $st->execute(); $st->reset();
        }
        $db->exec('commit');
        file_put_contents($mark, (string)$size);
    }
    return $out;
}

// The order in which campaigns are offered work: a priority QUEUE (order_campaigns). The highest priority with open
// games gets the workers; lower priorities only fill what it cannot use. Equal priorities share by weight.
//   DATA/priorities.json {"<substring of a campaign's meta.run path>": priority}   overrides meta.priority
//   DATA/weights.json    {"<substring of meta.run>": weight}   the longest matching substring names the campaign's
//       group (one line of work); otherwise the group is the experiment (experiment_of) with weight 1
function weights_of($DATA, $camps) {
    $ov = @json_decode(@file_get_contents("$DATA/priorities.json") ?: '{}', true) ?: [];
    // DATA/priorities_auto.json (iterate/line_yield.py): deck lines' priorities from their expected value (base tier
    // from priorities.json x return factor); applied after, so they win for the lines they name
    $ov = array_merge($ov, @json_decode(@file_get_contents("$DATA/priorities_auto.json") ?: '{}', true) ?: []);
    $wt = @json_decode(@file_get_contents("$DATA/weights.json") ?: '{}', true) ?: [];
    $out = [];
    foreach ($camps as $n => $m) {
        $run = $m['run'] ?? '';
        $pr = floatval($m['priority'] ?? 0);
        foreach ($ov as $pat => $p) if ($run !== '' && str_contains($run, $pat)) $pr = floatval($p);
        $key = null; $w = 0.0;
        foreach ($wt as $pat => $x)
            if ($run !== '' && str_contains($run, $pat) && ($key === null || strlen($pat) > strlen($key))) { $key = $pat; $w = floatval($x); }
        if ($key === null) { $key = experiment_of($m, $n); $w = 1.0; }
        $out[$n] = ['priority' => $pr, 'group' => $key, 'weight' => $w];
    }
    return $out;
}

// one experiment = one group: its run path without the last component when that names an arm/deck of a measurement
// (".../leyline-confirm/9962/burn" -> ".../leyline-confirm/9962"), else the campaign itself
function experiment_of($m, $n) {
    $run = $m['run'] ?? '';
    if (preg_match('#^(landm|landmeasure|llanc|leyline-confirm|leyline-ab)/#', $run))
        return 'exp:' . preg_replace('#^landmeasure/#', 'landm/', dirname($run));
    if (str_contains($run, 'xc-mono20') || preg_match('#runs/xc-#', $run)) return 'exp:' . $run;
    return "campaign:$n";
}

function order_campaigns($camps, $wts, $open) {
    // A queue: strictly by priority, so the top experiment gets every worker it can use and finishes first; lower
    // ones only fill what it leaves (an experiment that queues games a few hundred ahead cannot fill every worker).
    // Within one priority, groups share by weight (weighted random order, Efraimidis-Spirakis; weights are shares of
    // GAMES, so divided by per_slot); a group (one experiment, its weights.json pattern) keeps its campaigns together,
    // in random order so paired arms progress evenly.
    global $PER_SLOT;
    $tiers = [];   // priority -> group -> campaigns with open games
    foreach ($camps as $n => $m) {
        if (!empty($m['withdrawn']) || empty($open[$n])) continue;
        $w = $wts[$n];
        $tiers[(string)$w['priority']][$w['group'] ?? "campaign:$n"][] = $n;
    }
    uksort($tiers, fn($x, $y) => floatval($y) <=> floatval($x));
    $order = [];
    foreach ($tiers as $pr => $groups) {
        $key = [];
        foreach ($groups as $g => $ns) {
            $w = max(0.01, $wts[$ns[0]]['weight']) / max(1, intval($camps[$ns[0]]['per_slot'] ?? $PER_SLOT));
            $key[$g] = pow(mt_rand(1, mt_getrandmax()) / mt_getrandmax(), 1.0 / $w);
        }
        arsort($key);
        foreach (array_keys($key) as $g) {
            $ns = $groups[$g];
            shuffle($ns);
            array_push($order, ...$ns);
        }
    }
    return $order;
}

// A worker restarts its game workers whenever a job needs another harness or AI settings (meta tweaks): see
// dwell_filter for how that cost is weighed against the queue order.
$DWELL = 900;
function setting_of($m) { return ($m['harness'] ?? '') . '|' . ($m['tweaks'] ?? ''); }
function dwell_filter($db, $worker, $camps, $order, $now) {
    // Switching cost in the assignment: moving a machine to another harness/tweaks setting costs it a drain and a
    // restart of every game worker (~40 s, about 3 games per worker). The machine follows the queue order across a
    // setting change only when the work waiting under the new setting is worth at least SWITCH_FACTOR times that;
    // otherwise it takes the first package in queue order that has its current setting. With no work under its
    // current setting it switches at once. Same-setting packages cost nothing to move between.
    global $WTS, $SLOTS, $OPEN;
    $db->exec('create table if not exists worker_setting (worker text primary key, setting text, since real)');
    $st = $db->prepare('select setting, since from worker_setting where worker=?');
    $st->bindValue(1, $worker);
    $row = $st->execute()->fetchArray(SQLITE3_NUM);
    if (!$row || !$order || setting_of($camps[$order[0]]) === $row[0]) return [$order, true];
    // urgent work (priority >= 1000: harness canaries, the user's sealed project) is taken at once, however few of its
    // games are left (2026-10-15: the last open game of a sealed batch sat 7.5 h below the switching threshold)
    global $WTS;
    if (isset($WTS[$order[0]]) && floatval($WTS[$order[0]]['priority']) >= 1000) return [$order, true];
    $same = array_values(array_filter($order, fn($n) => setting_of($camps[$n]) === $row[0]));
    if (!$same) return [$order, true];
    // games open in ALL packages ahead of the machine's own next one, whatever their setting (2026-10-12: counting
    // only the first package's exact setting deadlocked the queue -- a harness canary split into three 12-game
    // campaigns, one per tweak set, sat first and never reached the threshold, and the 6000 games queued behind it
    // were never counted, so no machine ever switched)
    $waiting = 0;
    foreach ($order as $n) {
        if ($n === $same[0]) break;
        $waiting += $OPEN[$n] ?? 0;
    }
    // a v4+ client switches one worker at a time as each finishes (~10 s start-up each, no drain): far cheaper
    global $CLIENT_V;
    $switch_cost = ($CLIENT_V >= 4 ? 0.5 : 3) * max(1, $SLOTS);
    if ($waiting >= 10 * $switch_cost) return [$order, true];
    return [array_merge($same, array_values(array_diff($order, $same))), false];
}
function remember_setting($db, $worker, $setting, $now, $fresh) {
    $st = $db->prepare('select setting from worker_setting where worker=?');
    $st->bindValue(1, $worker);
    $row = $st->execute()->fetchArray(SQLITE3_NUM);
    if ($row && $row[0] === $setting && !$fresh) return;
    if ($row && $row[0] === $setting && $fresh) {   // the new draw kept the setting: a new slice starts now
        $u = $db->prepare('update worker_setting set since=? where worker=?');
        $u->bindValue(1, $now); $u->bindValue(2, $worker); $u->execute();
        return;
    }
    $u = $db->prepare('insert or replace into worker_setting values (?, ?, ?)');
    $u->bindValue(1, $worker); $u->bindValue(2, $setting); $u->bindValue(3, $now); $u->execute();
}

function hold_prefix($DATA) { return trim((string)@file_get_contents("$DATA/HOLD")); }

function open_counts($db) {
    $open = [];
    $r = $db->query("select campaign, count(*) from games where status='open' group by campaign");
    while ($row = $r->fetchArray(SQLITE3_NUM)) $open[$row[0]] = $row[1];
    return $open;
}

$a = $_GET['a'] ?? '';

// ---- the client watchdog (v7, 2026-10-17): a check every 5 min from each installed machine ----
// a=ctl&state=running|absent|stopped_by_user: records the check-in (table worker_watchdog) and answers whether a restart
// has been requested for this worker (DATA/ctl/<worker>.restart, written by iterate/queue_watchdog.py; consumed here).
// a=logtail (POST, text): the end of the worker's log, sent before the watchdog restarts it; kept in DATA/ctl/.
if ($a === 'ctl' || $a === 'logtail') {
    @mkdir("$DATA/ctl", 0777, true);
    $safe = preg_replace('/[^A-Za-z0-9_.-]/', '_', $worker);
    if ($a === 'ctl') {
        $db->exec('create table if not exists worker_watchdog (worker text primary key, seen real, state text, version int)');
        $st = $db->prepare('insert or replace into worker_watchdog values (?, ?, ?, ?)');
        $st->bindValue(1, $worker); $st->bindValue(2, microtime(true));
        $st->bindValue(3, substr((string)($_GET['state'] ?? ''), 0, 32)); $st->bindValue(4, intval($_GET['v'] ?? 0));
        $st->execute();
        $rf = "$DATA/ctl/$safe.restart";
        if (file_exists($rf)) {
            $why = trim((string)@file_get_contents($rf));
            @rename($rf, "$DATA/ctl/$safe.restart." . date('Ymd-His') . '.done');
            out(200, ['restart' => true, 'why' => $why]);
        }
        out(200, ['restart' => false]);
    }
    $body = substr((string)file_get_contents('php://input'), -200000);
    $why = substr((string)($_GET['why'] ?? ''), 0, 300);
    $f = "$DATA/ctl/$safe-" . date('Ymd-His') . '.log';
    file_put_contents($f, "# $why\n" . $body);
    @chmod($f, 0644);
    out(200, ['stored' => basename($f)]);
}
if ($a === 'job') {
    if (intval($_GET['v'] ?? 0) < $MIN_CLIENT) out(426, ['error' => 'please update forge-worker']);
    $slots = max(1, min(64, intval($_GET['slots'] ?? 1)));
    $camps = campaigns($DATA, $db);
    $wts = weights_of($DATA, $camps);
    $WTS = $wts;
    $now = microtime(true);
    $db->exec('begin immediate');
    $db->exec("update games set status='open', job=null, worker=null where status='leased' and lease_until < $now");
    $OPEN = open_counts($db);
    $SLOTS = $slots;
    $CLIENT_V = intval($_GET['v'] ?? 0);
    [$order, $fresh] = dwell_filter($db, $worker, $camps, order_campaigns($camps, $wts, $OPEN), $now);
    // harnesses this worker reported it cannot play (a=release) since it last started AND within the last
    // $FAIL_RETRY seconds: not offered. After that it is offered again, so a machine whose cause went away (2026-10-23:
    // Malwarebytes released the quarantined javac.exe) recovers by itself; a retry that still fails costs one handed-back job
    $started = max(floatval($_GET['started'] ?? 0), $now - $FAIL_RETRY);
    $failed = [];
    $hf = $db->prepare('select harness from harness_fail where worker=? and at > ?');
    $hf->bindValue(1, $worker); $hf->bindValue(2, $started);
    $hr = $hf->execute();
    while ($row = $hr->fetchArray(SQLITE3_NUM)) $failed[$row[0]] = true;
    if ($failed) $order = array_values(array_filter($order, fn($n) => !isset($failed[$camps[$n]['harness'] ?? ''])));
    // DATA/HOLD holds the whole queue for one project (2026-10-15, the user's sealed project: "the whole queue",
    // deck lines paused): only campaigns whose name starts with its text are issued; priority alone leaked ~70% of
    // the games to lower work in the gaps between batches, which workers then queued locally ahead of the next batch
    $hold = hold_prefix($DATA);
    // harness canaries (run 'canary/...') always pass: a new harness must be checkable during a hold
    if ($hold !== '') $order = array_values(array_filter($order, fn($n) => str_starts_with($n, $hold) || str_starts_with($camps[$n]['run'] ?? '', 'canary/')));
    foreach ($order as $name) {
        $meta = $camps[$name];
        $meta['priority'] = $wts[$name]['priority'];
        if (!empty($meta['withdrawn'])) continue;
        // meta "exclude": workers never offered this campaign (2026-10-17: harness canaries exclude the local jetson,
        // which took all 36 canary games in seconds so no Windows PC ever got the 6 the gate needs)
        if (in_array($worker, $meta['exclude'] ?? [], true)) continue;
        $per = intval($meta['per_slot'] ?? $PER_SLOT);
        $lease = intval($meta['lease_s'] ?? $LEASE);
        $st = $db->prepare("select gid from games where campaign=? and status='open' order by rowid limit ?");
        $st->bindValue(1, $name); $st->bindValue(2, $slots * $per);
        $r = $st->execute();
        $gids = [];
        while ($row = $r->fetchArray(SQLITE3_NUM)) $gids[] = $row[0];
        if (!$gids) continue;
        $job = bin2hex(random_bytes(8));
        $up = $db->prepare("update games set status='leased', job=?, worker=?, lease_until=? where campaign=? and gid=?");
        foreach ($gids as $g) {
            $up->bindValue(1, $job); $up->bindValue(2, $worker); $up->bindValue(3, $now + $lease);
            $up->bindValue(4, $name); $up->bindValue(5, $g); $up->execute(); $up->reset();
        }
        $ins = $db->prepare('insert into jobs (job, campaign, worker, created, n, done) values (?, ?, ?, ?, ?, null)');
        $ins->bindValue(1, $job); $ins->bindValue(2, $name); $ins->bindValue(3, $worker);
        $ins->bindValue(4, $now); $ins->bindValue(5, count($gids)); $ins->execute();
        // which client version asked (the version each machine runs)
        $cv = $db->prepare('insert or replace into worker_client (worker, version, seen, threads) values (?, ?, ?, ?)');
        $cv->bindValue(1, $worker); $cv->bindValue(2, intval($_GET['v'] ?? 0)); $cv->bindValue(3, $now); $cv->bindValue(4, $slots);
        $cv->execute();
        remember_setting($db, $worker, setting_of($meta), $now, $fresh);
        $db->exec('commit');
        $want = array_flip($gids);
        $games = []; $decks = [];
        $d = "$DATA/campaigns/$name";
        foreach (file("$d/games.jsonl", FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $l) {
            $g = json_decode($l, true);
            if (!$g || !isset($g['gid']) || !isset($want[$g['gid']])) continue;
            $games[] = $g;
            foreach (['a', 'b'] as $k)
                if (!isset($decks[$g[$k]])) $decks[$g[$k]] = file_get_contents("$d/decks/" . basename($g[$k]) . ".dck");
        }
        $ws = @json_decode(@file_get_contents("$DATA/workers.json") ?: '{}', true);   // per-machine settings
        out(200, ['job' => $job, 'campaign' => $name, 'priority' => intval($meta['priority'] ?? 0),
                  'threads' => intval($ws[$worker]['threads'] ?? 0),
                  'tweaks' => $meta['tweaks'] ?? '', 'harness' => $meta['harness'],
                  'lease_s' => $lease, 'games' => $games, 'decks' => $decks]);
    }
    $db->exec('commit');
    out(204);
}

if ($a === 'result' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $job = preg_replace('/[^0-9a-f]/', '', $_GET['job'] ?? '');
    $st = $db->prepare('select campaign, worker from jobs where job=?');
    $st->bindValue(1, $job);
    $row = $st->execute()->fetchArray(SQLITE3_NUM);
    if (!$row) out(404, ['error' => 'unknown job']);
    [$camp, $owner] = $row;
    if ($owner !== $worker) out(403, ['error' => 'job belongs to another worker']);
    $tmp = tempnam(sys_get_temp_dir(), 'fw');
    file_put_contents($tmp, file_get_contents('php://input'));
    $z = new ZipArchive();
    if ($z->open($tmp) !== true) { unlink($tmp); out(400, ['error' => 'bad archive']); }
    // Windows PowerShell 5.1's Compress-Archive writes "logs\x.log": accept "\" as a separator, normalise to "/"
    $entries = [];
    for ($i = 0; $i < $z->numFiles; $i++) {
        $n = str_replace('\\', '/', $z->getNameIndex($i));
        if (str_starts_with($n, '/') || str_contains($n, '..') || str_contains($n, ':')) { unlink($tmp); out(400, ['error' => 'bad path']); }
        $entries[$i] = $n;
    }
    $ri = array_search('results.jsonl', $entries, true);
    $res = $ri === false ? false : $z->getFromIndex($ri);
    if ($res === false) { unlink($tmp); out(400, ['error' => 'no results.jsonl']); }
    $now = microtime(true); $acc = 0; $dup = 0;
    $db->exec('begin immediate');
    $q = $db->prepare('select status from games where campaign=? and gid=?');
    $u = $db->prepare("update games set status='done', job=?, worker=?, done_at=? where campaign=? and gid=?");
    foreach (preg_split('/\r?\n/', trim($res)) as $l) {
        $r = json_decode($l, true);
        if (!$r || !isset($r['gid'])) continue;
        $q->bindValue(1, $camp); $q->bindValue(2, $r['gid']);
        $s = $q->execute()->fetchArray(SQLITE3_NUM); $q->reset();
        if (!$s) continue;
        if ($s[0] === 'done') { $dup++; continue; }
        $u->bindValue(1, $job); $u->bindValue(2, $worker); $u->bindValue(3, $now);
        $u->bindValue(4, $camp); $u->bindValue(5, $r['gid']); $u->execute(); $u->reset();
        $acc++;
    }
    $db->exec("update jobs set done=$now where job='$job'");
    $db->exec('commit');
    $part = intval($_GET['part'] ?? 0);
    $dest = "$DATA/campaigns/$camp/results/" . preg_replace('/[^A-Za-z0-9_.-]/', '_', $worker) . "/$job" . ($part ? "_p$part" : '') . ($acc ? '' : '.duplicate');
    @mkdir($dest, 0777, true);
    foreach ($entries as $i => $n) {
        if (str_ends_with($n, '/')) continue;
        @mkdir(dirname("$dest/$n"), 0777, true);
        file_put_contents("$dest/$n", $z->getFromIndex($i));
    }
    $z->close();
    unlink($tmp);
    out(200, ['accepted' => $acc, 'duplicates' => $dup]);
}

if ($a === 'release' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $job = (string)($_GET['job'] ?? '');
    $h = substr((string)($_GET['harness'] ?? ''), 0, 64);
    $why = substr((string)file_get_contents('php://input'), 0, 2000);
    $db->exec('begin immediate');
    $st = $db->prepare("update games set status='open', job=null, worker=null where job=? and worker=? and status='leased'");
    $st->bindValue(1, $job); $st->bindValue(2, $worker); $st->execute();
    $n = $db->changes();
    if ($h !== '') {
        $ins = $db->prepare('insert or replace into harness_fail (worker, harness, at, why) values (?, ?, ?, ?)');
        $ins->bindValue(1, $worker); $ins->bindValue(2, $h); $ins->bindValue(3, microtime(true)); $ins->bindValue(4, $why);
        $ins->execute();
    }
    $db->exec('commit');
    out(200, ['released' => $n]);
}

if ($a === 'withdrawn') {   // campaigns whose not-yet-started games workers should drop from their local queues
    $w = [];
    foreach (glob("$DATA/campaigns/*/meta.json") as $f) {
        $m = json_decode(file_get_contents($f), true);
        if (!empty($m['withdrawn'])) $w[] = basename(dirname($f));
        // under a hold, everything else too: workers drop its unstarted games (their leases lapse and reopen later)
        elseif (hold_prefix($DATA) !== '' && !str_starts_with(basename(dirname($f)), hold_prefix($DATA)) && !str_starts_with($m['run'] ?? '', 'canary/')) $w[] = basename(dirname($f));
    }
    out(200, ['withdrawn' => $w]);
}

if ($a === 'shares') {   // expected share of jobs per group right now: 2000 simulated draws, nothing leased
    $camps = campaigns($DATA, $db);
    $wts = weights_of($DATA, $camps);
    $open = open_counts($db);
    $first = []; $tot = 0;
    for ($i = 0; $i < 2000; $i++) {   // games per draw = per_slot (x the slots, the same for every campaign)
        $o = order_campaigns($camps, $wts, $open);
        if (!$o) break;
        $g = $wts[$o[0]]['group'] ?? "fallback:$o[0]";
        $n = max(1, intval($camps[$o[0]]['per_slot'] ?? $PER_SLOT));
        $first[$g] = ($first[$g] ?? 0) + $n; $tot += $n;
    }
    arsort($first);
    foreach ($first as $g => $c) $first[$g] = round($c / max(1, $tot), 3);
    out(200, ['shares' => $first]);
}

if ($a === 'status') {
    $c = []; $w = [];
    $r = $db->query('select campaign, status, count(*) from games group by campaign, status');
    while ($row = $r->fetchArray(SQLITE3_NUM)) $c[$row[0]][$row[1]] = $row[2];
    $r = $db->query("select worker, count(*), max(done_at) from games where status='done' group by worker");
    while ($row = $r->fetchArray(SQLITE3_NUM)) $w[$row[0]] = ['games' => $row[1], 'last' => $row[2]];
    $j = [];
    $r = $db->query("select job, worker, campaign, n, created, done from jobs order by created desc limit 20");
    while ($row = $r->fetchArray(SQLITE3_ASSOC)) $j[] = $row;
    $v = [];
    $r = $db->query("select worker, version, seen, threads from worker_client");
    while ($row = $r->fetchArray(SQLITE3_ASSOC)) $v[$row['worker']] = $row;
    out(200, ['campaigns' => $c, 'workers' => $w, 'clients' => $v, 'recent_jobs' => $j]);
}
out(404, ['error' => 'not found']);
