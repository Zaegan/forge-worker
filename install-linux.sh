#!/bin/bash
# forge-worker for Linux x86-64, one line, no admin rights:
#   curl -fsSL https://raw.githubusercontent.com/Zaegan/forge-worker/main/install-linux.sh | bash
# Installs PowerShell 7 into ~/opt/pwsh and the worker into ~/.forge-worker, asks for this machine's token, saves the
# settings and starts the worker in the background. The worker downloads Java and Forge on its first start.
# Game workers: logical CPUs - 2 (measured ~1 GB of RAM each); set THREADS=N before "bash" to choose another number.
set -e
D=$HOME/.forge-worker
SERVER=https://tmp.atlantiscertificateadmin.vip/forge
N=${THREADS:-$(( $(nproc) > 3 ? $(nproc) - 2 : 1 ))}
mkdir -p "$D" "$HOME/opt/pwsh"
if [ ! -x "$HOME/opt/pwsh/pwsh" ]; then
  echo "Installing PowerShell 7..."
  curl -fL https://github.com/PowerShell/PowerShell/releases/download/v7.4.6/powershell-7.4.6-linux-x64.tar.gz | tar -xz -C "$HOME/opt/pwsh"
  chmod +x "$HOME/opt/pwsh/pwsh"
fi
echo "Downloading the worker..."
curl -fsSLo "$D/worker.ps1" https://raw.githubusercontent.com/Zaegan/forge-worker/main/worker.ps1
read -rsp "Paste this machine's token and press Enter: " T < /dev/tty; echo
"$HOME/opt/pwsh/pwsh" -NoProfile -File "$D/worker.ps1" -Install -Server "$SERVER" -Threads "$N" -Token "$T"
chmod 600 "$D/settings.json"
cat > "$HOME/start-worker.sh" <<'EOS'
#!/bin/sh
setsid nohup "$HOME/opt/pwsh/pwsh" -NoProfile -File "$HOME/.forge-worker/worker.ps1" >> "$HOME/.forge-worker/worker.log" 2>&1 < /dev/null &
echo "worker started; log: ~/.forge-worker/worker.log"
EOS
cat > "$HOME/stop-worker.sh" <<'EOS'
#!/bin/sh
for p in $(ps -eo pid,args | awk '/[w]orker\.ps1/ {print $1}'); do kill -TERM -$(ps -o pgid= -p $p | tr -d ' ') 2>/dev/null; done
echo "worker stopped"
EOS
chmod +x "$HOME/start-worker.sh" "$HOME/stop-worker.sh"
"$HOME/start-worker.sh"
echo "Done: $N game workers. Stop with ~/stop-worker.sh, start again with ~/start-worker.sh."
