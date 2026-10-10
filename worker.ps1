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
    [switch]$Update                         # review and (after you confirm) install the newest worker.ps1; does not start work
)
$ClientVersion = 3                          # the server can ask clients older than its minimum to update
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
    @{ Server = $Server; Token = $Token } | ConvertTo-Json | Set-Content -Path $cfgFile -Encoding UTF8
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

function Log($m) { Write-Host ("{0} {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

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
    if (Test-Path (Join-Path $classes "MatchWorker.class")) { return $classes }
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
    & $javac -nowarn -encoding UTF-8 -d $classes -cp $jar $files 2>&1 | Where-Object { $_ -notmatch "^Note:" } | ForEach-Object { Log $_ }
    if (-not (Test-Path (Join-Path $classes "MatchWorker.class"))) { throw "harness did not compile" }
    return $classes
}

# ---- worker count: CPUs - 2, and at most what memory allows (each Forge JVM may use 2 GB) ----
if ($Threads -le 0) {
    $Threads = [Math]::Max(1, [Environment]::ProcessorCount - 2)
    try {
        $gb = if ($onWindows) { (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB }
              else { [double]((Get-Content /proc/meminfo | Select-String MemTotal).ToString().Split(" ", [StringSplitOptions]::RemoveEmptyEntries)[1]) / 1MB }
        $Threads = [Math]::Max(1, [Math]::Min($Threads, [Math]::Floor(($gb - 4) / 2.5)))
    } catch { }
}
Log "forge-worker: $Threads game workers, home $Dir"

# ---- game workers: long-lived Forge JVMs fed one game at a time from a shared queue (like forge-sim's engine) ----
$script:pool = New-Object System.Collections.ArrayList
$logs = Join-Path $Dir "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null

function StartWorker($classes, $tweaks, $key) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = "-Xmx2048m -Djava.awt.headless=true -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 " +
                     "`"-Dforge.sim.gamelog=$logs`" -Dforge.sim.tweaks=$tweaks -cp `"$jar$sep$classes`" MatchWorker"
    $psi.WorkingDirectory = $ForgeDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [Diagnostics.Process]::Start($psi)
    try { $p.PriorityClass = "BelowNormal" } catch { }
    return [pscustomobject]@{ P = $p; Out = $p.StandardOutput.ReadLineAsync(); Err = $p.StandardError.ReadLineAsync()
                              Ready = $false; Cur = $null; Key = $key; Dead = $false }
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
    $games = @($gamesJson | ConvertFrom-Json)
    # parts small enough for the server's request limit (8 MB): split a part in half until its zip fits
    $parts = New-Object System.Collections.ArrayList
    [void]$parts.Add(@($games | Where-Object { Test-Path (Join-Path $outs "$($_.gid).jsonl") }))
    $p = 0; $ok = $true
    while ($p -lt $parts.Count) {
        $part = $parts[$p]
        $stage = Join-Path $jd "upload_$p"
        if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
        $slogs = Join-Path $stage "logs"
        New-Item -ItemType Directory -Force -Path $slogs | Out-Null
        $res = foreach ($g in $part) {
            '{"gid":"' + $g.gid + '","game":' + (Get-Content (Join-Path $outs "$($g.gid).jsonl") -Tail 1) + '}'
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
$queue = New-Object System.Collections.Queue
$tries = @{}
$hold = $null; $curKey = $null; $active = 0
$uploads = New-Object System.Collections.ArrayList
$nextFetch = Get-Date; $fetched = 0; $stop = $false

function Finish($st, $played) {
    $st.Finished++; if ($played) { $st.Played++ }
    if ($st.Finished -lt $st.Total) { return }
    $min = ((Get-Date) - $st.T0).TotalMinutes
    Log ("batch {0}: played {1} games in {2:N1} min ({3:N0} games/min)" -f $st.Job.job, $st.Played, $min, ($st.Played / [Math]::Max($min, 0.01)))
    $gj = ConvertTo-Json -InputObject @($st.Job.games) -Compress -Depth 4
    [void]$uploads.Add((Start-Job -ScriptBlock $UploadBlock -ArgumentList $st.Jd, $st.Outs, $logs, $gj, $st.Job.job, $Server, $Token))
    $script:active--
}

while ($true) {
    # fetch the next batch while the current one still has work queued (no idle gap between batches)
    if (-not $stop -and -not $hold -and $queue.Count -lt $Threads -and (Get-Date) -ge $nextFetch -and -not ($Once -and $fetched -gt 0)) {
        $r = $null
        try {
            $r = Invoke-WebRequest -Uri "$Server/api.php?a=job&slots=$Threads&v=$ClientVersion" -Headers $headers -UseBasicParsing -TimeoutSec 120
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 426) {
                Log "the server needs a newer forge-worker: run 'Update Forge Worker' (it shows the changes and asks first)"; $stop = $true
            } else { Log "server unreachable ($($_.Exception.Message)); retrying in 60 s"; $nextFetch = (Get-Date).AddSeconds(60) }
        }
        if ($r -and $r.StatusCode -eq 204) {
            if ($active -eq 0 -and $queue.Count -eq 0) { Log "no work; checking again in 5 min" }
            if ($Once -and $active -eq 0) { $stop = $true }
            $nextFetch = (Get-Date).AddMinutes(5)
        } elseif ($r -and $r.StatusCode -eq 200) {
            $job = $r.Content | ConvertFrom-Json
            $fetched++
            Log "job $($job.job): $(@($job.games).Count) games from campaign $($job.campaign)"
            try {
                $st = PrepareJob $job
                $active++
                if ($null -eq $curKey -or $st.Key -eq $curKey) { $curKey = $st.Key; foreach ($g in $job.games) { $queue.Enqueue(@($g, $st)) } }
                else { $hold = $st }
                if ($Once) { $stop = $true }
            } catch { Log "stopping: $($_.Exception.Message)"; $stop = $true }
        }
    }
    # a batch needing another harness or settings waits until the current workers are idle, then restarts them
    if ($hold -and $queue.Count -eq 0 -and @($script:pool | Where-Object { $_.Cur }).Count -eq 0) {
        Log "new harness or settings: restarting the game workers"; StopPool
        $curKey = $hold.Key; foreach ($g in $hold.Job.games) { $queue.Enqueue(@($g, $hold)) }; $hold = $null
    }
    while ($queue.Count -gt 0 -and $script:pool.Count -lt $Threads -and $queue.Count -gt @($script:pool | Where-Object { -not $_.Cur }).Count) {
        $st0 = $queue.Peek()[1]
        [void]$script:pool.Add((StartWorker $st0.Classes $st0.Job.tweaks $st0.Key))
    }
    foreach ($w in @($script:pool)) {
        while ($w.Err.IsCompleted -and $w.Err.Result -ne $null) { $w.Err = $w.P.StandardError.ReadLineAsync() }
        while ($w.Out.IsCompleted -and $w.Out.Result -ne $null) {
            $line = $w.Out.Result
            if ($line -eq "@@READY") { $w.Ready = $true }
            elseif (($line.StartsWith("@@DONE ") -or $line.StartsWith("@@FAIL ")) -and $w.Cur) {
                $g = $w.Cur[0]; $st = $w.Cur[1]
                if ($line.StartsWith("@@FAIL ")) {
                    $tries[$g.gid] = 1 + [int]$tries[$g.gid]
                    if ($tries[$g.gid] -lt 3) { $queue.Enqueue($w.Cur) } else { Finish $st $false }
                } else { Finish $st $true }
                $w.Cur = $null
            }
            $w.Out = $w.P.StandardOutput.ReadLineAsync()
        }
        if ($w.P.HasExited) {
            # a timed-out game ends its JVM (by design); a game it never reported goes back on the queue
            if ($w.Cur) {
                $g = $w.Cur[0]; $st = $w.Cur[1]
                if (Test-Path (Join-Path $st.Outs "$($g.gid).jsonl")) { Finish $st $true }
                else {
                    $tries[$g.gid] = 1 + [int]$tries[$g.gid]
                    if ($tries[$g.gid] -lt 3) { $queue.Enqueue($w.Cur) } else { Finish $st $false }
                }
            }
            $w.Cur = $null; $w.Dead = $true
        } elseif ($w.Ready -and -not $w.Cur -and $queue.Count -gt 0 -and $w.Key -eq $queue.Peek()[1].Key) {
            $item = $queue.Dequeue(); $g = $item[0]; $st = $item[1]
            $w.P.StandardInput.WriteLine((($g.gid, (Join-Path $st.Decks "$($g.a).dck"), (Join-Path $st.Decks "$($g.b).dck"),
                $g.seed, $g.index, $g.timeout, (Join-Path $st.Outs "$($g.gid).jsonl")) -join "`t"))
            $w.P.StandardInput.Flush()
            $w.Cur = $item
        }
    }
    foreach ($w in @($script:pool | Where-Object { $_.Dead })) { $script:pool.Remove($w) }
    foreach ($u in @($uploads)) {
        Receive-Job $u | ForEach-Object { Log $_ }
        if ($u.State -ne "Running") { Receive-Job $u | ForEach-Object { Log $_ }; Remove-Job $u; $uploads.Remove($u) }
    }
    if ($stop -and $active -eq 0 -and $uploads.Count -eq 0) { StopPool; break }
    Start-Sleep -Milliseconds 200
}
