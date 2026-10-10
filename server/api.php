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
//   GET  api.php?a=status
// DATA/workers.json {"<worker name>": {"threads": N}} sets a machine's game-worker count (sent with each job; the
// client never exceeds its own limit of logical CPUs - 2).
// meta.json may also set "per_slot" (games per worker slot per batch), "lease_s" and "withdrawn": true.
umask(0);
$DATA = getenv('FORGE_DATA') ?: realpath(__DIR__ . '/../../uploads') . '/forge';
$LEASE = 1800;
$PER_SLOT = 25;
$MIN_CLIENT = 0;   // raise to make older workers stop and ask their owner to run "Update Forge Worker"

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
create table if not exists jobs (job text primary key, campaign text, worker text, created real, n integer, done real);");

function campaigns($DATA, $db) {
    $out = [];
    foreach (glob("$DATA/campaigns/*/games.jsonl") as $f) {
        $d = dirname($f);
        $name = basename($d);
        $m = @json_decode(@file_get_contents("$d/meta.json"), true);
        if (!is_array($m) || empty($m['harness'])) continue;   // not complete yet
        $out[$name] = $m;
        if (!empty($out[$name]['withdrawn'])) continue;   // withdrawn: nothing more is issued
        $mark = "$d/.registered";
        if (file_exists($mark) && filemtime($mark) >= filemtime($f)) continue;
        $db->exec('begin immediate');
        $st = $db->prepare('insert or ignore into games (campaign, gid) values (?, ?)');
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $l) {
            $g = json_decode($l, true);
            if (!$g || !isset($g['gid'])) continue;   // a line still being appended
            $st->bindValue(1, $name); $st->bindValue(2, $g['gid']); $st->execute(); $st->reset();
        }
        $db->exec('commit');
        touch($mark);
    }
    return $out;
}

// The order in which campaigns are offered work. Campaigns with a weight share the workers in proportion to it (a
// weighted random order, drawn again for every job), so no line of work starves; campaigns without one (priority <= 0:
// filler) only get work when no weighted campaign has any, highest priority first.
//   DATA/priorities.json {"<substring of a campaign's meta.run path>": priority}   overrides meta.priority
//   DATA/weights.json    {"<substring of meta.run>": weight}   the longest matching substring names the campaign's
//       group (one line of work: its campaigns split the group's weight); without a match, priority > 0 gives
//       weight priority / 5 for the campaign alone
function weights_of($DATA, $camps) {
    $ov = @json_decode(@file_get_contents("$DATA/priorities.json") ?: '{}', true) ?: [];
    $wt = @json_decode(@file_get_contents("$DATA/weights.json") ?: '{}', true) ?: [];
    $out = [];
    foreach ($camps as $n => $m) {
        $run = $m['run'] ?? '';
        $pr = intval($m['priority'] ?? 0);
        foreach ($ov as $pat => $p) if ($run !== '' && str_contains($run, $pat)) $pr = intval($p);
        $key = null; $w = 0.0;
        foreach ($wt as $pat => $x)
            if ($run !== '' && str_contains($run, $pat) && ($key === null || strlen($pat) > strlen($key))) { $key = $pat; $w = floatval($x); }
        if ($key === null && $pr > 0) { $key = "campaign:$n"; $w = $pr / 5.0; }
        $out[$n] = ['priority' => $pr, 'group' => $key, 'weight' => $w];
    }
    return $out;
}

function order_campaigns($camps, $wts, $open) {
    $groups = [];   // group -> its campaigns with open games
    $rest = [];
    foreach ($camps as $n => $m) {
        if (!empty($m['withdrawn']) || empty($open[$n])) continue;
        $w = $wts[$n];
        if ($w['weight'] > 0) $groups[$w['group']][] = $n; else $rest[] = $n;
    }
    // weighted random order of the groups (Efraimidis-Spirakis: key u^(1/w), largest first). Weights are shares of
    // GAMES: a job holds per_slot games per worker slot, so a group is drawn with probability weight / per_slot.
    global $PER_SLOT;
    $key = [];
    foreach ($groups as $g => $ns) {
        $w = $wts[$ns[0]]['weight'] / max(1, intval($camps[$ns[0]]['per_slot'] ?? $PER_SLOT));
        $key[$g] = pow(mt_rand(1, mt_getrandmax()) / mt_getrandmax(), 1.0 / $w);
    }
    arsort($key);
    $order = [];
    foreach (array_keys($key) as $g) {
        $ns = $groups[$g];
        usort($ns, fn($x, $y) => $wts[$y]['priority'] <=> $wts[$x]['priority']);   // within a group: priority
        array_push($order, ...$ns);
    }
    $rnd = []; foreach ($rest as $n) $rnd[$n] = mt_rand();
    usort($rest, fn($x, $y) => ($wts[$y]['priority'] <=> $wts[$x]['priority']) ?: ($rnd[$x] <=> $rnd[$y]));
    return array_merge($order, $rest);
}

function open_counts($db) {
    $open = [];
    $r = $db->query("select campaign, count(*) from games where status='open' group by campaign");
    while ($row = $r->fetchArray(SQLITE3_NUM)) $open[$row[0]] = $row[1];
    return $open;
}

$a = $_GET['a'] ?? '';
if ($a === 'job') {
    if (intval($_GET['v'] ?? 0) < $MIN_CLIENT) out(426, ['error' => 'please update forge-worker']);
    $slots = max(1, min(64, intval($_GET['slots'] ?? 1)));
    $camps = campaigns($DATA, $db);
    $wts = weights_of($DATA, $camps);
    $now = microtime(true);
    $db->exec('begin immediate');
    $db->exec("update games set status='open', job=null, worker=null where status='leased' and lease_until < $now");
    $order = order_campaigns($camps, $wts, open_counts($db));
    foreach ($order as $name) {
        $meta = $camps[$name];
        $meta['priority'] = $wts[$name]['priority'];
        if (!empty($meta['withdrawn'])) continue;
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
        $ins = $db->prepare('insert into jobs values (?, ?, ?, ?, ?, null)');
        $ins->bindValue(1, $job); $ins->bindValue(2, $name); $ins->bindValue(3, $worker);
        $ins->bindValue(4, $now); $ins->bindValue(5, count($gids)); $ins->execute();
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

if ($a === 'withdrawn') {   // campaigns whose not-yet-started games workers should drop from their local queues
    $w = [];
    foreach (glob("$DATA/campaigns/*/meta.json") as $f) {
        $m = json_decode(file_get_contents($f), true);
        if (!empty($m['withdrawn'])) $w[] = basename(dirname($f));
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
    out(200, ['campaigns' => $c, 'workers' => $w, 'recent_jobs' => $j]);
}
out(404, ['error' => 'not found']);
