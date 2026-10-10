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
    [switch]$Uninstall                      # remove the Desktop shortcut (downloads and settings are kept)
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest's progress bar makes downloads very slow on PS 5.1
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$onWindows = ($PSVersionTable.PSVersion.Major -lt 6) -or $IsWindows
$sep = if ($onWindows) { ";" } else { ":" }
$exe = if ($onWindows) { ".exe" } else { "" }
if (-not $Dir) { $Dir = if ($onWindows) { Join-Path $env:LOCALAPPDATA "forge-worker" } else { Join-Path $HOME ".forge-worker" } }
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

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
        Write-Host "Installed: double-click 'Forge Worker' on your Desktop to start it; close its window to stop. Starting it now..."
    }
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

function PlayBatch($job) {
    $jd = Join-Path $Dir "jobs/$($job.job)"
    $decks = Join-Path $jd "decks"; $logs = Join-Path $jd "logs"; $outs = Join-Path $jd "out"
    New-Item -ItemType Directory -Force -Path $decks, $logs, $outs | Out-Null
    foreach ($p in $job.decks.PSObject.Properties) {
        [IO.File]::WriteAllText((Join-Path $decks "$($p.Name).dck"), $p.Value)
    }
    $classes = Harness $job.harness
    $todo = @($job.games)
    for ($round = 0; $round -lt 3 -and $todo.Count -gt 0; $round++) {
        $n = [Math]::Min($Threads, $todo.Count)
        $procs = @()
        for ($k = 0; $k -lt $n; $k++) {
            $lines = for ($i = $k; $i -lt $todo.Count; $i += $n) {
                $g = $todo[$i]
                ($g.gid, (Join-Path $decks "$($g.a).dck"), (Join-Path $decks "$($g.b).dck"), $g.seed, $g.index, $g.timeout,
                 (Join-Path $outs "$($g.gid).jsonl")) -join "`t"
            }
            $tin = Join-Path $jd "tasks_${round}_$k.txt"
            [IO.File]::WriteAllLines($tin, [string[]]$lines)
            $jargs = @("-Xmx2048m", "-Djava.awt.headless=true", "-Dio.netty.tryReflectionSetAccessible=true",
                       "-Dfile.encoding=UTF-8", "`"-Dforge.sim.gamelog=$logs`"", "-Dforge.sim.tweaks=$($job.tweaks)",
                       "-cp", "`"$jar$sep$classes`"", "MatchWorker")
            $p = Start-Process -FilePath $java -ArgumentList $jargs -WorkingDirectory $ForgeDir -NoNewWindow -PassThru `
                 -RedirectStandardInput $tin -RedirectStandardOutput (Join-Path $jd "worker_${round}_$k.out") `
                 -RedirectStandardError (Join-Path $jd "worker_${round}_$k.err")
            try { $p.PriorityClass = "BelowNormal" } catch { }
            $procs += $p
        }
        $procs | Wait-Process
        $todo = @($todo | Where-Object { -not (Test-Path (Join-Path $outs "$($_.gid).jsonl")) })
        if ($todo.Count -gt 0) { Log "$($todo.Count) games unfinished (a timed-out game ends its worker); retrying" }
    }
    # upload in parts small enough for the server's request limit (8 MB): split a part in half until its zip fits
    $parts = New-Object System.Collections.ArrayList
    [void]$parts.Add(@($job.games | Where-Object { Test-Path (Join-Path $outs "$($_.gid).jsonl") }))
    $p = 0; $ok = $true
    while ($p -lt $parts.Count) {
        $part = $parts[$p]
        $stage = Join-Path $jd "upload_$p"
        if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
        $slogs = Join-Path $stage "logs"
        New-Item -ItemType Directory -Force -Path $slogs | Out-Null
        $res = foreach ($g in $part) {
            '{"gid":"' + $g.gid + '","game":' + (Get-Content (Join-Path $outs "$($g.gid).jsonl") -Tail 1) + '}'
            Get-ChildItem $logs -Filter "*_$($g.seed)_$($g.index).*" | Copy-Item -Destination $slogs
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
                $r = Invoke-WebRequest -Uri "$Server/api.php?a=result&job=$($job.job)&part=$p" -Method Post -InFile $zip `
                     -ContentType "application/zip" -Headers $headers -UseBasicParsing -TimeoutSec 600
                Log "uploaded $($job.job) part $p ($($part.Count) games): $($r.Content)"
                $sent = $true
            } catch { Log "upload failed ($($_.Exception.Message)); retrying in 60 s"; Start-Sleep 60 }
        }
        if (-not $sent) { $ok = $false }
        $p++
    }
    if ($ok) { Remove-Item $jd -Recurse -Force } else { Log "some uploads failed; the files stay in $jd" }
}

while ($true) {
    try {
        $r = Invoke-WebRequest -Uri "$Server/api.php?a=job&slots=$Threads" -Headers $headers -UseBasicParsing -TimeoutSec 120
    } catch { Log "server unreachable ($($_.Exception.Message)); retrying in 60 s"; Start-Sleep 60; continue }
    if ($r.StatusCode -eq 204) { Log "no work; checking again in 5 min"; if ($Once) { break }; Start-Sleep 300; continue }
    $job = $r.Content | ConvertFrom-Json
    Log "job $($job.job): $($job.games.Count) games from campaign $($job.campaign)"
    PlayBatch $job
    if ($Once) { break }
}
