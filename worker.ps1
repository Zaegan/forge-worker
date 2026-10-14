<#
.SYNOPSIS
  forge-worker: plays Forge (MTG) AI-vs-AI games for a forge-sim job server and uploads complete game logs.

.DESCRIPTION
  Runs on any internet-connected Windows PC (Windows PowerShell 5.1 or PowerShell 7; no admin rights, nothing installed).
  First run downloads into -Home: a portable Java 21 (Eclipse Temurin), Forge 2.0.15 from Forge's official GitHub release,
  and the forge-sim harness sources at the commit each job asks for (compiled locally). Then it loops: fetch a batch of
  games, play them on (logical CPUs - 2) parallel Forge workers at below-normal priority (2 threads stay free for other
  work), upload the results and every game's full log, repeat. Several PCs can run it at once; each needs its own token.
  Stop it any time (Ctrl+C); an unfinished batch is simply handed to another worker after its lease expires.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File worker.ps1 -Server https://example.org/forge -Token YOUR_TOKEN
#>
param(
    [string]$Server = "",                   # job server (saved by -Install; then optional)
    [string]$Token = "",                    # this PC's token (saved by -Install; then optional)
    [int]$Threads = 0,                      # default: logical CPUs - 2 (and no more than memory allows)
    [string]$Dir = "",                      # working folder (default %LOCALAPPDATA%\forge-worker)
    [string]$JavaHome = "",                 # use an existing JDK 21 instead of downloading one
    [string]$ForgeDir = "",                 # use an existing Forge 2.0.15 folder instead of downloading it
    [string]$Repo = "Zaegan/forge-worker",  # GitHub project holding the harness sources
    [string]$HarnessSrc = "",               # local harness folder (testing) instead of downloading from GitHub
    [switch]$Once,                          # play one batch and exit (testing)
    [switch]$Install,                       # save -Server/-Token and put a "Forge Worker" shortcut on the Desktop
    [switch]$Uninstall,                     # remove the Desktop shortcut (downloads and settings are kept)
    [switch]$Update,                        # review and (after you confirm) install the newest worker.ps1; does not start work
    [int]$IdleWait = 20                     # seconds to wait before asking again when the server has no work
)
$Waiting = 2                                # games queued in each game worker beyond the one it is playing
$ClientVersion = 6                          # the server can ask clients older than its minimum to update
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest's progress bar makes downloads very slow on PS 5.1
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$onWindows = ($PSVersionTable.PSVersion.Major -lt 6) -or $IsWindows
$sep = if ($onWindows) { ";" } else { ":" }
$exe = if ($onWindows) { ".exe" } else { "" }
if (-not $Dir) { $Dir = if ($onWindows) { Join-Path $env:LOCALAPPDATA "forge-worker" } else { Join-Path $HOME ".forge-worker" } }
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

# ---- manual update (never automatic): show what changed on GitHub, install only after the user types YES ----
if ($Update) {
    $self = if (Test-Path (Join-Path $Dir "worker.ps1")) { Join-Path $Dir "worker.ps1" } else { $PSCommandPath }
    $new = (Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$Repo/main/worker.ps1" -UseBasicParsing -TimeoutSec 60).Content
    if ($new -eq [IO.File]::ReadAllText($self)) { Write-Host "Already up to date."; Read-Host "Press Enter to close"; exit 0 }
    Write-Host "A newer worker.ps1 is on GitHub. Recent changes to it:"
    $commits = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/commits?path=worker.ps1&per_page=5" -UseBasicParsing
    foreach ($c in $commits) { Write-Host ("  {0}  {1}  {2}" -f $c.commit.author.date, $c.sha.Substring(0, 7), $c.commit.message.Split("`n")[0]) }
    Write-Host "Review them at https://github.com/$Repo/commits/main/worker.ps1"
    if ((Read-Host "Type YES to install this version") -ne "YES") { Write-Host "Not updated."; exit 0 }
    [IO.File]::WriteAllText($self, $new)
    Write-Host "Updated $self."
    Read-Host "Press Enter to close"
    exit 0
}

# ---- settings, and start-at-logon ----
$cfgFile = Join-Path $Dir "settings.json"
if (Test-Path $cfgFile) {
    $cfg = Get-Content $cfgFile -Raw | ConvertFrom-Json
    if (-not $Server) { $Server = $cfg.Server }
    if (-not $Token) { $Token = $cfg.Token }
    if ($Threads -le 0 -and $cfg.Threads) { $Threads = [int]$cfg.Threads }
}
$lnk = if ($onWindows) { Join-Path ([Environment]::GetFolderPath("Desktop")) "Forge Worker.lnk" } else { "" }
if ($Uninstall) {
    if ($lnk -and (Test-Path $lnk)) { Remove-Item $lnk }
    $ulnk = if ($onWindows) { Join-Path ([Environment]::GetFolderPath("Desktop")) "Update Forge Worker.lnk" } else { "" }
    if ($ulnk -and (Test-Path $ulnk)) { Remove-Item $ulnk }
    Write-Host "Removed the Forge Worker shortcut (downloads and settings stay in $Dir)."
    exit 0
}
if (-not $Server -or -not $Token) { throw "give -Server and -Token (once; -Install saves them)" }
if ($Install) {
    $save = @{ Server = $Server; Token = $Token }
    if ($Threads -gt 0) { $save.Threads = $Threads }   # -Install -Threads N: the shortcut uses N workers from now on
    $save | ConvertTo-Json | Set-Content -Path $cfgFile -Encoding UTF8
    $self = Join-Path $Dir "worker.ps1"
    if ($PSCommandPath -ne $self) { Copy-Item $PSCommandPath $self -Force }
    if ($onWindows) {
        $sh = New-Object -ComObject WScript.Shell
        $l = $sh.CreateShortcut($lnk)
        $l.TargetPath = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
        $l.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$self`""
        $l.WorkingDirectory = $Dir
        $l.Description = "forge-worker: plays Forge games for the forge-sim job server"
        $l.Save()
        $u = $sh.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "Update Forge Worker.lnk"))
        $u.TargetPath = $l.TargetPath
        $u.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$self`" -Update"
        $u.WorkingDirectory = $Dir
        $u.Description = "Review and install a newer forge-worker (never runs by itself)"
        $u.Save()
        Write-Host "Installed. Double-click 'Forge Worker' on your Desktop to start; close its window to stop."
        Write-Host "Updates are never automatic: 'Update Forge Worker' shows what changed and asks before installing."
    }
    exit 0   # install only: it does not start a (second) worker
}
$Server = $Server.TrimEnd("/")
$headers = @{ Authorization = "Bearer $Token" }

$script:LogFile = Join-Path $Dir "worker.log"
function Log($m) {
    $line = "{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $m
    Write-Host $line
    try { Add-Content -Path $script:LogFile -Value $line } catch { }
}
# any unhandled error: record it, keep the window open (a shortcut-started window would otherwise vanish), then exit
trap {
    Log "FATAL: $($_.Exception.Message)"
    Log "  at $($_.InvocationInfo.PositionMessage -replace '\s+', ' ')"
    try { StopPool } catch { }
    Read-Host "forge-worker stopped because of the error above. Press Enter to close"
    exit 1
}

function Download($url, $dest) {
    if (Test-Path $dest) { return }
    Log "downloading $url"
    Invoke-WebRequest -Uri $url -OutFile "$dest.part" -UseBasicParsing
    Move-Item "$dest.part" $dest
}

# ---- Java ----
if (-not $JavaHome) {
    $jdk = Join-Path $Dir "jdk"
    if (-not (Test-Path $jdk)) {
        $os = if ($onWindows) { "windows" } else { "linux" }
        $ext = if ($onWindows) { "zip" } else { "tar.gz" }
        $arch = if ([Environment]::Is64BitOperatingSystem) { "x64" } else { "x86" }
        $pkg = Join-Path $Dir "jdk21.$ext"
        Download "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse" $pkg
        $tmp = Join-Path $Dir "jdk-unpack"
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        if ($onWindows) { Expand-Archive -Path $pkg -DestinationPath $tmp -Force } else { tar -xzf $pkg -C $tmp }
        Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $jdk
        Remove-Item $tmp -Recurse -Force
    }
    $JavaHome = $jdk
}
$java = Join-Path $JavaHome "bin/java$exe"
$javac = Join-Path $JavaHome "bin/javac$exe"

# ---- Forge 2.0.15 (official release) ----
if (-not $ForgeDir) {
    $ForgeDir = Join-Path $Dir "forge-2.0.15"
    if (-not (Test-Path (Join-Path $ForgeDir "forge-gui-desktop-2.0.15-jar-with-dependencies.jar"))) {
        $pkg = Join-Path $Dir "forge-installer-2.0.15.tar.bz2"
        Download "https://github.com/Card-Forge/forge/releases/download/forge-2.0.15/forge-installer-2.0.15.tar.bz2" $pkg
        New-Item -ItemType Directory -Force -Path $ForgeDir | Out-Null
        Log "unpacking Forge"
        tar -xjf $pkg -C $ForgeDir   # Windows 10+ ships bsdtar, which reads .tar.bz2
        if ($LASTEXITCODE -ne 0) { throw "could not unpack Forge" }
    }
}
$jar = Join-Path $ForgeDir "forge-gui-desktop-2.0.15-jar-with-dependencies.jar"
if (-not (Test-Path $jar)) { throw "Forge jar not found in $ForgeDir" }

# ---- harness at a given commit, compiled once ----
function Harness($commit) {
    $dir = Join-Path $Dir "harness/$commit"
    $classes = Join-Path $dir "classes"
    if (Test-Path (Join-Path $classes "MatchWorker.class")) {
        try { (Get-Item $dir).LastWriteTime = Get-Date } catch { }   # most recently USED: housekeeping keeps these
        return $classes
    }
    if (-not $HarnessSrc) {
        if ($commit -notmatch '^[0-9a-f]{40}$') { throw "refusing harness '$commit': not a full commit id" }
        # only compile a commit that is part of $Repo's own main branch: compare main...commit is "behind" or
        # "identical" exactly when the commit is an ancestor of main (a fork's commit is "diverged" or unknown)
        try { $cmp = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/compare/main...$commit" -UseBasicParsing -TimeoutSec 60 }
        catch { throw "refusing harness $commit`: GitHub does not know it as part of $Repo ($($_.Exception.Message))" }
        if ($cmp.status -ne "behind" -and $cmp.status -ne "identical") {
            throw "refusing harness $commit`: it is not on $Repo's main branch (status '$($cmp.status)')"
        }
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if ($HarnessSrc) { $src = $HarnessSrc } else {
        $zip = Join-Path $dir "src.zip"
        Download "https://codeload.github.com/$Repo/zip/$commit" $zip
        Expand-Archive -Path $zip -DestinationPath (Join-Path $dir "src") -Force
        $src = Join-Path (Get-ChildItem (Join-Path $dir "src") -Directory | Select-Object -First 1).FullName "harness"
    }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $files = (Get-ChildItem $src -Filter *.java).FullName
    Log "compiling the harness ($commit)"
    $ct = Get-Date
    & $javac -nowarn -encoding UTF-8 -d $classes -cp $jar $files 2>&1 | Where-Object { $_ -notmatch "^Note:" } | ForEach-Object { Log $_ }
    if (-not (Test-Path (Join-Path $classes "MatchWorker.class"))) { throw "harness did not compile" }
    Log ("harness compiled in {0:N0} s" -f ((Get-Date) - $ct).TotalSeconds)
    return $classes
}

# ---- worker count: CPUs - 2, and at most what memory allows (each Forge JVM may use 2 GB) ----
# $Ceiling is this PC's limit, which nothing from the server can exceed (2 threads always stay free); the server may
# set a lower count per machine, which is how the best count is found and kept without touching the PC.
$Ceiling = [Math]::Max(1, [Environment]::ProcessorCount - 2)
try {
    $gb = if ($onWindows) { (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB }
          else { [double]((Get-Content /proc/meminfo | Select-String MemTotal).ToString().Split(" ", [StringSplitOptions]::RemoveEmptyEntries)[1]) / 1MB }
    $Ceiling = [Math]::Max(1, [Math]::Min($Ceiling, [Math]::Floor(($gb - 4) / 2.5)))
} catch { }
if ($Threads -gt 0) { $Ceiling = $Threads }   # an explicit (or saved) -Threads is the ceiling
$Threads = $Ceiling
Log "forge-worker: $Threads game workers (at most $Ceiling), home $Dir"

# ---- game workers: long-lived Forge JVMs fed one game at a time from a shared queue (like forge-sim's engine) ----
$script:pool = New-Object System.Collections.ArrayList
$logs = Join-Path $Dir "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null
# Housekeeping, at startup and every hour, so nothing on this PC grows without limit:
# - job folders and logs over a day old (a window closed mid-batch, or an upload that failed five times): their
#   leases have long expired and the server has handed those games out again;
# - worker.log over 20 MB becomes worker.old.log (one previous file kept);
# - compiled harness versions other than the three most recently used (every job touches its version's folder);
# - the installer downloads once unpacked.
function Housekeeping {
    $cut = (Get-Date).AddDays(-1); $freed = 0
    foreach ($old in @(Get-ChildItem (Join-Path $Dir "jobs") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt $cut })) {
        $freed += (Get-ChildItem $old.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        Remove-Item $old.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
    foreach ($old in @(Get-ChildItem $logs -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt $cut })) {
        $freed += $old.Length; Remove-Item $old.FullName -Force -ErrorAction SilentlyContinue
    }
    foreach ($old in @(Get-ChildItem (Join-Path $Dir "harness") -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -Skip 3)) {
        $freed += (Get-ChildItem $old.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        Remove-Item $old.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
    # the installer downloads, once unpacked (~500 MB: Forge's .tar.bz2 and the JDK archive)
    $unpacked = @{ "forge-installer-2.0.15.tar.bz2" = "forge-2.0.15/forge-gui-desktop-2.0.15-jar-with-dependencies.jar";
                   "jdk21.zip" = "jdk/bin/java.exe"; "jdk21.tar.gz" = "jdk/bin/java" }
    foreach ($a in $unpacked.Keys) {
        $arc = Join-Path $Dir $a
        if ((Test-Path $arc) -and (Test-Path (Join-Path $Dir $unpacked[$a]))) {
            $freed += (Get-Item $arc).Length; Remove-Item $arc -Force -ErrorAction SilentlyContinue
        }
    }
    try {
        if ((Test-Path $script:LogFile) -and (Get-Item $script:LogFile).Length -gt 20MB) {
            Move-Item $script:LogFile (Join-Path $Dir "worker.old.log") -Force
        }
    } catch { }
    if ($freed -gt 0) { Log ("housekeeping: removed {0:N0} MB" -f ($freed / 1MB)) }
}
Housekeeping
$nextHousekeeping = (Get-Date).AddHours(1)

function StartWorker($classes, $tweaks, $key) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = "-Xmx2048m -Djava.awt.headless=true -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 " +
                     "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 " +
                     "`"-Dforge.sim.gamelog=$logs`" -Dforge.sim.tweaks=$tweaks -cp `"$jar$sep$classes`" MatchWorker"
    $psi.WorkingDirectory = $ForgeDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    # v6: read the game worker's output as UTF-8 (Windows' default code page turned "Kíli" into "KÃ­li" in results)
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8; $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $psi.CreateNoWindow = $true
    $p = [Diagnostics.Process]::Start($psi)
    try { $p.PriorityClass = "BelowNormal" } catch { }
    return [pscustomobject]@{ P = $p; Out = $p.StandardOutput.ReadLineAsync(); Err = $p.StandardError.ReadLineAsync()
                              Ready = $false; Pend = (New-Object System.Collections.ArrayList); Key = $key; Dead = $false; Retiring = $false }
}

function StopPool {
    foreach ($w in $script:pool) { try { $w.P.StandardInput.Close() } catch { }; try { if (-not $w.P.WaitForExit(30000)) { $w.P.Kill() } } catch { } }
    $script:pool.Clear()
}

# ---- uploads run in the background (a separate PowerShell), so the game workers never wait for them ----
$UploadBlock = {
    param($jd, $outs, $logs, $gamesJson, $jobId, $Server, $Token)
    $ErrorActionPreference = "Stop"; $ProgressPreference = "SilentlyContinue"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $headers = @{ Authorization = "Bearer $Token" }
    # Windows PowerShell 5.1's ConvertFrom-Json emits a JSON array as ONE object; piping through ForEach-Object unrolls
    # it on every PowerShell version (without this, 5.1 saw one "game" whose gid was all 550 gids: path too long)
    $games = @($gamesJson | ConvertFrom-Json | ForEach-Object { $_ })
    # parts small enough for the server's request limit (8 MB): split a part in half until its zip fits
    $parts = New-Object System.Collections.ArrayList
    function SafeName($gid) { ($gid -replace '[^A-Za-z0-9_.-]', '_') + '-' + [BitConverter]::ToString([Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes([string]$gid))).Replace('-', '').Substring(0, 8) }
    [void]$parts.Add(@($games | Where-Object { Test-Path (Join-Path $outs "$(SafeName $_.gid).jsonl") }))
    $p = 0; $ok = $true
    while ($p -lt $parts.Count) {
        $part = $parts[$p]
        $stage = Join-Path $jd "upload_$p"
        if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
        $slogs = Join-Path $stage "logs"
        New-Item -ItemType Directory -Force -Path $slogs | Out-Null
        $res = foreach ($g in $part) {
            '{"gid":' + (ConvertTo-Json -InputObject ([string]$g.gid) -Compress) + ',"game":' + (Get-Content (Join-Path $outs "$(SafeName $g.gid).jsonl") -Tail 1) + '}'
            Get-ChildItem $logs -Filter "$($g.a)_vs_$($g.b)_$($g.seed)_$($g.index).*" | Copy-Item -Destination $slogs
        }
        [IO.File]::WriteAllLines((Join-Path $stage "results.jsonl"), [string[]]@($res | Where-Object { $_ -is [string] }))
        $zip = Join-Path $jd "upload_$p.zip"
        if (Test-Path $zip) { Remove-Item $zip }
        Compress-Archive -Path (Join-Path $stage "results.jsonl"), $slogs -DestinationPath $zip
        if ((Get-Item $zip).Length -gt 7MB -and $part.Count -gt 1) {
            $h = [Math]::Floor($part.Count / 2)
            $parts[$p] = @($part[0..($h - 1)]); [void]$parts.Insert($p + 1, @($part[$h..($part.Count - 1)]))
            continue
        }
        $sent = $false
        for ($try = 0; $try -lt 5 -and -not $sent; $try++) {
            try {
                $r = Invoke-WebRequest -Uri "$Server/api.php?a=result&job=$jobId&part=$p" -Method Post -InFile $zip `
                     -ContentType "application/zip" -Headers $headers -UseBasicParsing -TimeoutSec 600
                "uploaded $jobId part $p ($($part.Count) games): $($r.Content)"
                $sent = $true
            } catch { "upload of $jobId failed ($($_.Exception.Message)); retrying in 60 s"; Start-Sleep 60 }
        }
        if (-not $sent) { $ok = $false }
        $p++
    }
    if ($ok) {
        foreach ($g in $games) { Get-ChildItem $logs -Filter "$($g.a)_vs_$($g.b)_$($g.seed)_$($g.index).*" | Remove-Item }
        Remove-Item $jd -Recurse -Force
    } else { "some uploads of $jobId failed; its files stay in $jd" }
}

# file names from game ids: ids may contain characters Windows forbids in paths (e.g. "B|B00|3"); the short hash keeps
# two ids that sanitise alike apart. The id itself is still what is reported to the server.
function SafeName($gid) { ($gid -replace '[^A-Za-z0-9_.-]', '_') + '-' + [BitConverter]::ToString([Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes([string]$gid))).Replace('-', '').Substring(0, 8) }

function PrepareJob($job) {
    $jd = Join-Path $Dir "jobs/$($job.job)"
    $decks = Join-Path $jd "decks"; $outs = Join-Path $jd "out"
    New-Item -ItemType Directory -Force -Path $decks, $outs | Out-Null
    foreach ($p in $job.decks.PSObject.Properties) { [IO.File]::WriteAllText((Join-Path $decks "$($p.Name).dck"), $p.Value) }
    $classes = Harness $job.harness
    return [pscustomobject]@{ Job = $job; Jd = $jd; Decks = $decks; Outs = $outs; Classes = $classes
                              Key = "$($job.harness)|$($job.tweaks)"; Total = @($job.games).Count; Finished = 0; Played = 0
                              T0 = Get-Date }
}

# ---- the scheduler: one shared queue across batches; the next batch is fetched before this one runs dry ----
# games of different harness/tweaks settings may be queued together: each game goes to a worker of its setting, and
# workers are switched to another setting one at a time, as each becomes idle (no drain-and-restart of the whole pool)
$queue = New-Object System.Collections.ArrayList
$tries = @{}
$active = 0
$uploads = New-Object System.Collections.ArrayList
$nextFetch = Get-Date; $fetched = 0; $stop = $false
$nextWithdrawCheck = Get-Date
$nextProgress = (Get-Date).AddMinutes(3); $script:gamesDone = 0; $lastDone = 0; $lastProg = Get-Date; $announcedReady = $false

function Finish($st, $played) {
    $st.Finished++; if ($played) { $st.Played++; $script:gamesDone++ }
    if ($st.Finished -lt $st.Total) { return }
    $min = ((Get-Date) - $st.T0).TotalMinutes
    Log ("batch {0}: played {1} games in {2:N1} min ({3:N0} games/min)" -f $st.Job.job, $st.Played, $min, ($st.Played / [Math]::Max($min, 0.01)))
    $gj = ConvertTo-Json -InputObject @($st.Job.games) -Compress -Depth 4
    [void]$uploads.Add((Start-Job -ScriptBlock $UploadBlock -ArgumentList $st.Jd, $st.Outs, $logs, $gj, $st.Job.job, $Server, $Token))
    $script:active--
}

while ($true) {
    # fetch the next batch while the current one still has work queued (no idle gap between batches)
    if (-not $stop -and $queue.Count -lt $Threads -and (Get-Date) -ge $nextFetch -and -not ($Once -and $fetched -gt 0)) {
        $r = $null
        try {
            $r = Invoke-WebRequest -Uri "$Server/api.php?a=job&slots=$Threads&v=$ClientVersion" -Headers $headers -UseBasicParsing -TimeoutSec 120
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 426) {
                Log "the server needs a newer forge-worker: run 'Update Forge Worker' (it shows the changes and asks first)"; $stop = $true
            } else { Log "server unreachable ($($_.Exception.Message)); retrying in 60 s"; $nextFetch = (Get-Date).AddSeconds(60) }
        }
        if ($r -and $r.StatusCode -eq 204) {
            if ($active -eq 0 -and $queue.Count -eq 0 -and -not $script:saidNoWork) { Log "no work right now; checking every $IdleWait s"; $script:saidNoWork = $true }
            if ($Once -and $active -eq 0) { $stop = $true }
            $nextFetch = (Get-Date).AddSeconds($IdleWait)
        } elseif ($r -and $r.StatusCode -eq 200) {
            $job = $r.Content | ConvertFrom-Json
            $fetched++; $script:saidNoWork = $false
            if ($job.threads -and [int]$job.threads -gt 0) {
                $want = [Math]::Min([int]$job.threads, $Ceiling)
                if ($want -ne $Threads) { Log "server set the game workers to $want (this PC allows at most $Ceiling)"; $Threads = $want }
            }
            Log "job $($job.job): $(@($job.games).Count) games from campaign $($job.campaign)"
            try {
                $st = PrepareJob $job
                $active++
                $st | Add-Member -NotePropertyName Prio -NotePropertyValue ([int]$job.priority)
                # a higher-priority campaign (an experiment waiting on results) goes in front of bulk work
                $items = @($queue.ToArray())
                $queue.Clear()
                foreach ($x in @($items | Where-Object { $_[1].Prio -ge $st.Prio })) { [void]$queue.Add($x) }
                foreach ($g in $job.games) { [void]$queue.Add(@($g, $st)) }
                foreach ($x in @($items | Where-Object { $_[1].Prio -lt $st.Prio })) { [void]$queue.Add($x) }
                if ($Once) { $stop = $true }
            } catch {
                # e.g. the harness check refused the job, or GitHub was unreachable: play none of it (its lease expires
                # and the server re-issues it), wait, and carry on
                Log "skipping job $($job.job): $($_.Exception.Message); trying again in 5 min"
                $nextFetch = (Get-Date).AddMinutes(5)
            }
        }
    }
    # drop queued (not yet started) games of campaigns the orchestrator has withdrawn; running games just finish
    if ((Get-Date) -ge $nextWithdrawCheck -and $queue.Count -gt 0) {
        $nextWithdrawCheck = (Get-Date).AddSeconds(15)
        try {
            $wd = @((Invoke-RestMethod -Uri "$Server/api.php?a=withdrawn" -Headers $headers -UseBasicParsing -TimeoutSec 30).withdrawn)
            if ($wd.Count -gt 0) {
                $keep = New-Object System.Collections.ArrayList; $dropped = 0
                foreach ($item in @($queue.ToArray())) {
                    if ($wd -contains $item[1].Job.campaign) { $dropped++; Finish $item[1] $false } else { [void]$keep.Add($item) }
                }
                $queue = $keep
                if ($dropped) { Log "dropped $dropped queued games of withdrawn campaigns" }
            }
        } catch { }
    }
    # per setting: games queued, workers running it
    $need = @{}; foreach ($x in $queue) { $need[$x[1].Key] = 1 + [int]$need[$x[1].Key] }
    $have = @{}; foreach ($w in $script:pool) { if (-not $w.Retiring) { $have[$w.Key] = 1 + [int]$have[$w.Key] } }
    # grow the pool: a worker for the setting of the earliest queued game whose setting has fewer workers than games
    while ($script:pool.Count -lt $Threads) {
        $pick = $null
        foreach ($x in $queue) { if ([int]$have[$x[1].Key] -lt [int]$need[$x[1].Key]) { $pick = $x[1]; break } }
        if (-not $pick) { break }
        [void]$script:pool.Add((StartWorker $pick.Classes $pick.Job.tweaks $pick.Key))
        $have[$pick.Key] = 1 + [int]$have[$pick.Key]
    }
    foreach ($w in @($script:pool)) {
        while ($w.Err.IsCompleted -and $w.Err.Result -ne $null) { $w.Err = $w.P.StandardError.ReadLineAsync() }
        while ($w.Out.IsCompleted -and $w.Out.Result -ne $null) {
            $line = $w.Out.Result
            if ($line -eq "@@READY") { $w.Ready = $true }
            elseif ($line.StartsWith("@@DONE ") -or $line.StartsWith("@@FAIL ")) {
                # games run in the order written; the report names the game
                $tag = ($line.Substring(7) -split ' ', 2)[0]
                $item = $null
                foreach ($x in $w.Pend) { if ([string]$x[0].gid -eq $tag) { $item = $x; break } }
                if ($item) {
                    $w.Pend.Remove($item)
                    $g = $item[0]; $st = $item[1]
                    if ($line.StartsWith("@@FAIL ")) {
                        $tries[$g.gid] = 1 + [int]$tries[$g.gid]
                        if ($tries[$g.gid] -lt 3) { [void]$queue.Add($item) } else { Finish $st $false }
                    } else { Finish $st $true }
                }
            }
            $w.Out = $w.P.StandardOutput.ReadLineAsync()
        }
        if ($w.P.HasExited) {
            # a timed-out game ends its JVM (by design); games it never reported (the one playing and those waiting)
            # go back on the queue
            foreach ($item in @($w.Pend)) {
                $g = $item[0]; $st = $item[1]
                if (Test-Path (Join-Path $st.Outs "$(SafeName $g.gid).jsonl")) { Finish $st $true }
                else {
                    $tries[$g.gid] = 1 + [int]$tries[$g.gid]
                    if ($tries[$g.gid] -lt 3) { [void]$queue.Add($item) } else { Finish $st $false }
                }
            }
            $w.Pend.Clear(); $w.Dead = $true
        } elseif ($w.Ready -and $w.Pend.Count -eq 0 -and -not $w.Retiring -and $script:pool.Count -gt $Threads -and
                  @($script:pool | Where-Object { -not $_.Retiring }).Count -gt $Threads) {
            # more workers than the target: retire this idle one (it exits when its input closes)
            $w | Add-Member -NotePropertyName Retiring -NotePropertyValue $true -Force
            try { $w.P.StandardInput.Close() } catch { }
        } elseif ($w.Ready -and $w.Pend.Count -eq 0 -and -not $w.Retiring -and $queue.Count -gt 0 -and [int]$need[$w.Key] -eq 0) {
            # idle, and nothing queued for its setting while other work waits: retire it; the pool refill starts a
            # worker for the waiting setting in its place (one worker at a time, never the whole pool)
            $w | Add-Member -NotePropertyName Retiring -NotePropertyValue $true -Force
            try { $w.P.StandardInput.Close() } catch { }
            if (-not $script:saidSwitch) { Log "switching game workers to another setting, one at a time as they finish"; $script:saidSwitch = $true }
        } elseif ($w.Ready -and -not $w.Retiring) {
            # keep each worker's own queue topped up: the game playing plus $Waiting more, so it starts the next game
            # the moment one ends (no dependence on this loop's timing or on an upload starting)
            while ($w.Pend.Count -lt 1 + $Waiting -and [int]$need[$w.Key] -gt 0) {
                $idx = -1
                for ($i = 0; $i -lt $queue.Count; $i++) { if ($queue[$i][1].Key -eq $w.Key) { $idx = $i; break } }
                if ($idx -lt 0) { break }
                $item = $queue[$idx]; $queue.RemoveAt($idx); $need[$w.Key] = [int]$need[$w.Key] - 1
                $g = $item[0]; $st = $item[1]; $script:saidSwitch = $false
                try {
                    $w.P.StandardInput.WriteLine((($g.gid, (Join-Path $st.Decks "$($g.a).dck"), (Join-Path $st.Decks "$($g.b).dck"),
                        $g.seed, $g.index, $g.timeout, (Join-Path $st.Outs "$(SafeName $g.gid).jsonl")) -join "`t"))
                    $w.P.StandardInput.Flush()
                    [void]$w.Pend.Add($item)
                } catch {
                    # the game worker ended ("Broken pipe"): this used to stop the whole client (2026-10-11 03:39).
                    # Put its games back, drop it; the pool refill starts a new one.
                    Log "a game worker stopped unexpectedly ($($_.Exception.Message)); starting a new one"
                    [void]$queue.Add($item)
                    foreach ($x in @($w.Pend)) { [void]$queue.Add($x) }
                    $w.Pend.Clear()
                    try { if (-not $w.P.HasExited) { $w.P.Kill() } } catch { }
                    $w.Dead = $true
                    break
                }
            }
        }
    }
    foreach ($w in @($script:pool | Where-Object { $_.Dead })) { $script:pool.Remove($w) }
    if (-not $announcedReady -and $script:pool.Count -gt 0 -and @($script:pool | Where-Object { -not $_.Ready }).Count -eq 0) {
        $announcedReady = $true; Log "$($script:pool.Count) game workers ready"
    }
    if ((Get-Date) -ge $nextHousekeeping) { Housekeeping; $nextHousekeeping = (Get-Date).AddHours(1) }
    if ((Get-Date) -ge $nextProgress) {
        $m = ((Get-Date) - $lastProg).TotalMinutes
        if ($script:gamesDone -gt $lastDone) {
            Log ("progress: {0} games done this session, {1:N0} games/min over the last {2:N0} min, {3} queued" -f `
                 $script:gamesDone, (($script:gamesDone - $lastDone) / [Math]::Max($m, 0.01)), $m, $queue.Count)
        }
        $lastDone = $script:gamesDone; $lastProg = Get-Date; $nextProgress = (Get-Date).AddMinutes(3)
    }
    foreach ($u in @($uploads)) {
        # an upload's errors are logged, never fatal: the games stay leased and the server re-issues them later
        $je = $null
        Receive-Job $u -ErrorAction SilentlyContinue -ErrorVariable je | ForEach-Object { Log $_ }
        foreach ($e in @($je)) { if ($e) { Log "upload error: $e" } }
        if ($u.State -ne "Running") { Remove-Job $u -Force; $uploads.Remove($u) }
    }
    if ($stop -and $active -eq 0 -and $uploads.Count -eq 0) { StopPool; break }
    Start-Sleep -Milliseconds 1000   # each worker holds its next games itself, so this loop need not be quick
}
