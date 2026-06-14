---
name: poketerm
description: Sync your terminal to phone/tablet — monitor Claude Code sessions from any browser.
---

# PokeTerm Skill

Starts the PokeTerm server with a public tunnel so you can access your Claude Code session from anywhere.

## When to invoke

`/poketerm` — user wants to see their terminal from phone or tablet.

## Steps

### 1. Ensure tmux

```bash
brew list tmux &>/dev/null || brew install tmux
```

### 2. Detect or create tmux session

```bash
SAVED_PWD="$PWD"
if [ -n "$TMUX" ]; then
    TMUX_SESSION=$(tmux display-message -p '#S')
else
    if tmux has-session -t work 2>/dev/null; then
        TMUX_SESSION="work"
    else
        tmux new-session -d -s work -c "$SAVED_PWD"
        TMUX_SESSION="work"
    fi
fi
```

### 3. Start server if not already running

```bash
if lsof -ti:8765 >/dev/null 2>&1; then
    echo "Server already running"
else
    cd /Users/zengzitong/个人ai服务器
    TOKEN=$(head -c 12 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 12)
    TERM_TOKEN="$TOKEN" TERM_WORKSPACE="$SAVED_PWD" TERM_TMUX_SESSION="$TMUX_SESSION" \
      mvn spring-boot:run -q > /dev/null 2>&1 &
    sleep 8
fi
```

### 4. Start Cloudflare Tunnel

```bash
# Kill any existing cloudflared tunnel for this port
pkill -f "cloudflared.*localhost:8765" 2>/dev/null

# Start tunnel in background, capture output
cloudflared tunnel --url http://localhost:8765 > /tmp/poketerm-tunnel.log 2>&1 &

# Wait for tunnel URL (up to 15s)
for i in $(seq 1 15); do
    TUNNEL_URL=$(grep -o 'https://[^ ]*\.trycloudflare\.com' /tmp/poketerm-tunnel.log | head -1)
    [ -n "$TUNNEL_URL" ] && break
    sleep 1
done
```

### 5. Tell the user

The server banner prints the local Network URL and Token.

Relay everything together — local + public:

```
PokeTerm ready — tmux session: <session>

  On same WiFi:
    http://<local-ip>:8765

  Anywhere:
    <tunnel_url>

  Token: <token>

Open on your phone/tablet. Same Claude Code session on both devices.
```

If the tmux session was just created:

```
Start Claude Code in tmux first:
  tmux attach -t work
  claude
```
