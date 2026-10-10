# forge-worker

Lends a Windows PC's spare CPU to the forge-sim project: it plays [Forge](https://github.com/Card-Forge/forge)
(Magic: The Gathering) AI-vs-AI games handed out by a job server and uploads the results with every game's full log.

## Running it

You need: Windows 10 or 11, an internet connection, and a **token** from the project owner (one per PC). The server
address is `https://tmp.atlantiscertificateadmin.vip/forge`.

### Step by step

1. **Download** this project: on its GitHub page click the green **Code** button, then **Download ZIP**.
2. **Extract** the ZIP: right-click it in your Downloads folder, choose **Extract All…**, then **Extract**. You get a
   folder `forge-worker-main` (possibly with another `forge-worker-main` inside it: use the one containing `worker.ps1`).
3. **Open a command window in that folder**: open the folder in File Explorer, click the address bar, type `cmd` and
   press Enter.
4. **Start the worker** by typing this (one line; put your own token at the end) and pressing Enter:

   ```
   run-worker.cmd -Server https://tmp.atlantiscertificateadmin.vip/forge -Token YOUR_TOKEN
   ```

5. **Leave the window open.** It prints a line now and then: downloads and setup on the first run (several minutes,
   ~500 MB), then `job ...: N games` when it starts a batch and `uploaded ...` when a batch is done. When there is no
   work it waits and checks again every 5 minutes.
6. **To stop**, press **Ctrl+C** (answer `Y` if asked) or close the window. Nothing is lost: an unfinished batch is
   given to another worker later. To start again, repeat steps 3-4; setup is not repeated.

If you prefer PowerShell to the command window, use
`powershell -ExecutionPolicy Bypass -File .\worker.ps1 -Server ... -Token ...`. Typing `./worker.ps1` on its own fails
with "running scripts is disabled on this system": that is Windows' default script policy, and the two commands above
run the script without changing it.

### What it does

The first run downloads, into `%LOCALAPPDATA%\forge-worker` (nothing is installed, no admin rights):
- a portable Java 21 (Eclipse Temurin, from adoptium.net),
- Forge 2.0.15 from Forge's official GitHub release (~300 MB),
- this project's harness sources at the commit each job asks for, compiled locally.

It then runs one Forge game worker per logical CPU minus 2 (2 threads stay free for your own work; also capped by
memory, about 2.5 GB per worker) at below-normal priority, plays a batch, uploads it, and repeats. Stop it with Ctrl+C
any time: an unfinished batch goes back to the pool after its lease expires. Several PCs can run it at once.

Options: `-Threads N` (fixed worker count), `-Dir PATH` (working folder), `-JavaHome`, `-ForgeDir` (reuse existing
installs), `-Once` (one batch, then exit).

## Contents

- `worker.ps1`: the client.
- `harness/`: the forge-sim game harness (AI decision rules layered over Forge's AI, match runner, telemetry).
- `server/api.php`: the job server as one PHP file (PHP 8 with SQLite3 and Zip), for any PHP web host; data and
  tokens (stored as SHA-256 hashes) live outside the web root. Uploads are split to stay under the host's request limit.
- `server/jobserver.py`: the same API in Python (standard library only), for local testing.

Games are deterministic given their seed: a game played here gives exactly the result the project's own machines get.

## License

GPL-3.0 (see LICENSE), like Forge, whose classes the harness builds against.
