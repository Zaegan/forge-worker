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
//   GET  api.php?a=status
umask(0);
$DATA = getenv('FORGE_DATA') ?: realpath(__DIR__ . '/../../uploads') . '/forge';
$LEASE = 1800;
$PER_SLOT = 25;

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
        $out[$name] = json_decode(file_get_contents("$d/meta.json"), true);
        $mark = "$d/.registered";
        if (file_exists($mark) && filemtime($mark) >= filemtime($f)) continue;
        $db->exec('begin immediate');
        $st = $db->prepare('insert or ignore into games (campaign, gid) values (?, ?)');
        foreach (file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $l) {
            $g = json_decode($l, true);
            $st->bindValue(1, $name); $st->bindValue(2, $g['gid']); $st->execute(); $st->reset();
        }
        $db->exec('commit');
        touch($mark);
    }
    return $out;
}

$a = $_GET['a'] ?? '';
if ($a === 'job') {
    $slots = max(1, min(64, intval($_GET['slots'] ?? 1)));
    $camps = campaigns($DATA, $db);
    uasort($camps, fn($x, $y) => ($y['priority'] ?? 0) <=> ($x['priority'] ?? 0));
    $now = microtime(true);
    $db->exec('begin immediate');
    $db->exec("update games set status='open', job=null, worker=null where status='leased' and lease_until < $now");
    foreach ($camps as $name => $meta) {
        $st = $db->prepare("select gid from games where campaign=? and status='open' limit ?");
        $st->bindValue(1, $name); $st->bindValue(2, $slots * $PER_SLOT);
        $r = $st->execute();
        $gids = [];
        while ($row = $r->fetchArray(SQLITE3_NUM)) $gids[] = $row[0];
        if (!$gids) continue;
        $job = bin2hex(random_bytes(8));
        $up = $db->prepare("update games set status='leased', job=?, worker=?, lease_until=? where campaign=? and gid=?");
        foreach ($gids as $g) {
            $up->bindValue(1, $job); $up->bindValue(2, $worker); $up->bindValue(3, $now + $LEASE);
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
            if (!isset($want[$g['gid']])) continue;
            $games[] = $g;
            foreach (['a', 'b'] as $k)
                if (!isset($decks[$g[$k]])) $decks[$g[$k]] = file_get_contents("$d/decks/" . basename($g[$k]) . ".dck");
        }
        out(200, ['job' => $job, 'campaign' => $name, 'tweaks' => $meta['tweaks'] ?? '', 'harness' => $meta['harness'],
                  'lease_s' => $LEASE, 'games' => $games, 'decks' => $decks]);
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

if ($a === 'status') {
    $c = []; $w = [];
    $r = $db->query('select campaign, status, count(*) from games group by campaign, status');
    while ($row = $r->fetchArray(SQLITE3_NUM)) $c[$row[0]][$row[1]] = $row[2];
    $r = $db->query("select worker, count(*), max(done_at) from games where status='done' group by worker");
    while ($row = $r->fetchArray(SQLITE3_NUM)) $w[$row[0]] = ['games' => $row[1], 'last' => $row[2]];
    out(200, ['campaigns' => $c, 'workers' => $w]);
}
out(404, ['error' => 'not found']);
