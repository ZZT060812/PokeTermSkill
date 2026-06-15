---
name: poketerm
description: Sync your terminal to phone/tablet — monitor Claude Code sessions from any browser.
argument-hint: "[stop | status]"
---

# /poketerm

Start PokeTerm server + Cloudflare Tunnel. Output only the connection URLs and token — nothing else.

**Subcommands:**
- `/poketerm` — start server + tunnel (default)
- `/poketerm stop` — kill tmux session, stop server and tunnel
- `/poketerm status` — show whether server/tunnel are running

## Prerequisite

One-time setup: `export POKETERM_HOME="$HOME/个人ai服务器"` in `~/.zshrc`.

---

## Start (default)

Run this script, then present ONLY the connection info:

```bash
POKETERM_HOME="${POKETERM_HOME:-$HOME/个人ai服务器}"

# 1. Ensure tmux
if ! tmux has-session -t work 2>/dev/null; then
    tmux new-session -d -s work -c "$PWD"
fi

# 2. Start server (skip if already running)
if ! lsof -ti:8765 >/dev/null 2>&1; then
    TOKEN=$(head -c 12 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 12)
    cd "$POKETERM_HOME"
    TERM_TOKEN="$TOKEN" TERM_WORKSPACE="$PWD" TERM_TMUX_SESSION="work" \
      TERM_AUTO_RESUME="claude --resume" \
      mvn spring-boot:run -q > /dev/null 2>&1 &
    sleep 8
else
    # Read token from running server env
    TOKEN=$(ps e -p $(lsof -ti:8765 | head -1) 2>/dev/null | tr ' ' '\n' | grep '^TERM_TOKEN=' | cut -d= -f2)
    [ -z "$TOKEN" ] && TOKEN="(check server log)"
fi

# 3. Cloudflare Tunnel
pkill -f "cloudflared.*localhost:8765" 2>/dev/null
cloudflared tunnel --url http://localhost:8765 > /tmp/poketerm-tunnel.log 2>&1 &
for i in $(seq 1 15); do
    TUNNEL_URL=$(grep -o 'https://[^ ]*\.trycloudflare\.com' /tmp/poketerm-tunnel.log | head -1)
    [ -n "$TUNNEL_URL" ] && break
    sleep 1
done

# 4. Local IP
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "localhost")

echo "=== POKETERM ==="
echo "SESSION=work"
echo "LOCAL=http://${LOCAL_IP}:8765"
echo "REMOTE=${TUNNEL_URL}"
echo "TOKEN=${TOKEN}"
echo "=== END ==="
```

Output format:

```
PokeTerm ready — tmux: work

  Same WiFi:  http://<local-ip>:8765
  Anywhere:   <tunnel-url>
  Token:      <token>
```

---

## Stop

Kill the tmux session, server, and tunnel. Run:

```bash
# Kill tunnel
pkill -f "cloudflared.*localhost:8765" 2>/dev/null && echo "Tunnel stopped" || echo "Tunnel was not running"

# Kill server
pkill -f "mvn.*spring-boot:run" 2>/dev/null
pkill -f "java.*remoteterm" 2>/dev/null
sleep 1
lsof -ti:8765 | xargs kill 2>/dev/null && echo "Server stopped" || echo "Server was not running"

# Kill tmux session
tmux kill-session -t work 2>/dev/null && echo "Tmux session 'work' killed" || echo "Tmux session 'work' was not running"
```

Then confirm: "PokeTerm stopped — tmux, server, tunnel all down."

---

## Status

Check what's running:

```bash
echo "=== POKETERM STATUS ==="
lsof -ti:8765 >/dev/null 2>&1 && echo "Server: running on :8765" || echo "Server: stopped"
pgrep -f "cloudflared.*localhost:8765" >/dev/null 2>&1 && echo "Tunnel: running" || echo "Tunnel: stopped"
tmux has-session -t work 2>/dev/null && echo "Tmux: session 'work' active" || echo "Tmux: session 'work' not found"
echo "=== END ==="
```
