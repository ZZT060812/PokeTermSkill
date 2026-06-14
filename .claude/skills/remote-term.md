---
name: remote-term
description: Start the remote terminal server to monitor AI sessions from phone/tablet.
---

# Remote AI Terminal Skill

Starts a remote terminal server. Uses tmux for shared terminal sessions — you see the same Claude Code output on both devices.

## When to invoke

User invokes `/remote-term` or says things like:
- "start remote" / "open remote" / "let me check from phone"
- "I'm stepping out but want to monitor"
- "connect my phone to this session"

## Steps

### 0. Ensure tmux is installed

```bash
brew list tmux &>/dev/null || brew install tmux
```

### 1. Detect or create tmux session

Save the current directory, then detect if we're inside tmux:

```bash
SAVED_PWD="$PWD"

# If already inside tmux, use that session
if [ -n "$TMUX" ]; then
    TMUX_SESSION=$(tmux display-message -p '#S')
    echo "SESSION=$TMUX_SESSION"
else
    # Check if a 'work' session already exists
    if tmux has-session -t work 2>/dev/null; then
        TMUX_SESSION="work"
        echo "SESSION=work (existing)"
    else
        # Create new detached session in the current directory
        tmux new-session -d -s work -c "$SAVED_PWD"
        TMUX_SESSION="work"
        echo "SESSION=work (created)"
    fi
fi
```

### 2. Check if server is running

```bash
lsof -ti:8765 2>/dev/null && echo "RUNNING" || echo "STOPPED"
```

### 3a. If STOPPED — start with tmux session

```bash
PROJECT_DIR="/Users/zengzitong/个人ai服务器"
cd "$PROJECT_DIR"
TOKEN=$(head -c 12 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 12)
TERM_TOKEN="$TOKEN" TERM_WORKSPACE="$SAVED_PWD" TERM_TMUX_SESSION="$TMUX_SESSION" \
  mvn spring-boot:run -q > /dev/null 2>&1 &
sleep 8
curl -s -o /dev/null http://localhost:8765
```

### 3b. If RUNNING — report existing

```
Remote terminal already running at http://localhost:8765
```

### 4. Get local network IP

```bash
ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "localhost"
```

### 5. Output to user

If tmux session was just created, tell the user to start Claude Code there:

```
Remote terminal ready — tmux session: work

  Local:   http://localhost:8765
  Network: http://<ip>:8765
  Token:   <token>

Open in any browser. Both devices share the same tmux session.

If you haven't started Claude Code in tmux yet, run:
  tmux attach -t work
  claude
  (then /resume to continue your session)

Phone/tablet will see the exact same screen.
```

If already inside tmux (Claude Code is running there):

```
Remote terminal ready — tmux session: <name>

  Local:   http://localhost:8765
  Network: http://<ip>:8765
  Token:   <token>

Your phone will see the same Claude Code session. Both devices share input.
```

### 6. Cross-network (only if asked)

```bash
cloudflared tunnel --url http://localhost:8765
```

## How it works

```
主力机 Terminal.app              手机浏览器
      │                              │
      ▼                              ▼
┌──────────────────────────────────────┐
│           tmux session "work"        │
│  ┌────────────────────────────┐     │
│  │ zsh                        │     │
│  │ └── claude                 │◄───►│ 完全同步
│  │     > 分析中...             │     │
│  └────────────────────────────┘     │
└──────────────────────────────────────┘
```

The server uses `TERM_TMUX_SESSION` env var to `tmux attach` instead of spawning a new shell. Both devices see identical terminal output and can type commands.

## Management

```bash
# Stop server
pkill -f "com.remoteterm.App"

# Stop tmux session
tmux kill-session -t work

# Restart
/remote-term
```
