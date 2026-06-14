---
name: poketerm
description: Sync your terminal to phone/tablet — monitor Claude Code sessions from any browser.
---

# PokeTerm Skill

Starts the PokeTerm server so you can share your Claude Code session to any browser.

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

### 4. Tell the user

The server prints a banner with Network URL and Token. Relay them:

```
PokeTerm ready — tmux session: <session>

  Network: http://<ip>:8765
  Token:   <token>

Open on your phone/tablet. Same terminal, same Claude Code.
```

If the tmux session was just created, remind:

```
Start Claude Code in tmux first:
  tmux attach -t work
  claude
```

### 5. Cross-network (only if asked)

```bash
cloudflared tunnel --url http://localhost:8765
```
