#!/bin/bash
# PokeTerm launcher — start / stop / status
# Auto-detects project root from script location

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
PORT=8765
TMUX_SESSION="work"

# Resolve workspace: use current directory (where Claude/user is)
WORKSPACE="${POKETERM_WORKSPACE:-$PWD}"

usage() {
    echo "Usage: poketerm.sh {start|stop|status}"
    exit 1
}

do_start() {
    # 1. Ensure tmux
    local SESSION_NEW=false
    if ! tmux has-session -t "$TMUX_SESSION" 2>/dev/null; then
        tmux new-session -d -s "$TMUX_SESSION" -c "$WORKSPACE"
        echo "[poketerm] tmux session '$TMUX_SESSION' created"
        SESSION_NEW=true
    fi

    # 1.5 Auto-resume Claude in the tmux session (only if new session)
    if $SESSION_NEW; then
        sleep 1  # let tmux shell init
        tmux send-keys -t "$TMUX_SESSION" "claude --resume" Enter
        echo "[poketerm] auto-resume sent"
    fi

    TOKEN_FILE="/tmp/poketerm-token"

    # 2. Start server
    if lsof -ti:$PORT >/dev/null 2>&1; then
        echo "[poketerm] Server already running on :$PORT"
        TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null)
        [ -z "$TOKEN" ] && TOKEN="(unknown, run 'cat /tmp/poketerm-token')"
    else
        TOKEN=$(head -c 12 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 12)
        echo "$TOKEN" > "$TOKEN_FILE"
        cd "$PROJECT_ROOT"
        TERM_TOKEN="$TOKEN" \
        TERM_WORKSPACE="$WORKSPACE" \
        TERM_TMUX_SESSION="$TMUX_SESSION" \
          mvn spring-boot:run -q > /dev/null 2>&1 &
        sleep 8
        echo "[poketerm] Server started on :$PORT"
    fi

    # 3. Cloudflare Tunnel
    pkill -f "cloudflared.*localhost:$PORT" 2>/dev/null || true
    cloudflared tunnel --url "http://localhost:$PORT" > /tmp/poketerm-tunnel.log 2>&1 &
    for i in $(seq 1 15); do
        TUNNEL_URL=$(grep -o 'https://[^ ]*\.trycloudflare\.com' /tmp/poketerm-tunnel.log 2>/dev/null | head -1)
        [ -n "$TUNNEL_URL" ] && break
        sleep 1
    done

    # 4. Local IP
    LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "localhost")

    # 5. Output
    echo "=== POKETERM ==="
    echo "SESSION=$TMUX_SESSION"
    echo "LOCAL=http://${LOCAL_IP}:$PORT"
    echo "REMOTE=${TUNNEL_URL:-unknown}"
    echo "TOKEN=$TOKEN"
    echo "=== END ==="
}

do_stop() {
    pkill -f "cloudflared.*localhost:$PORT" 2>/dev/null && echo "[poketerm] Tunnel stopped" || echo "[poketerm] Tunnel was not running"
    pkill -f "mvn.*spring-boot:run" 2>/dev/null || true
    pkill -f "java.*remoteterm" 2>/dev/null || true
    sleep 1
    lsof -ti:$PORT | xargs kill 2>/dev/null && echo "[poketerm] Server stopped" || echo "[poketerm] Server was not running"
    tmux kill-session -t "$TMUX_SESSION" 2>/dev/null && echo "[poketerm] tmux session '$TMUX_SESSION' killed" || echo "[poketerm] tmux session was not running"
    rm -f /tmp/poketerm-token /tmp/poketerm-tunnel.log
}

do_status() {
    echo "=== POKETERM STATUS ==="
    lsof -ti:$PORT >/dev/null 2>&1 && echo "Server: running on :$PORT" || echo "Server: stopped"
    pgrep -f "cloudflared.*localhost:$PORT" >/dev/null 2>&1 && echo "Tunnel: running" || echo "Tunnel: stopped"
    tmux has-session -t "$TMUX_SESSION" 2>/dev/null && echo "Tmux: session '$TMUX_SESSION' active" || echo "Tmux: session '$TMUX_SESSION' not found"
    echo "=== END ==="
}

case "${1:-}" in
    start)  do_start ;;
    stop)   do_stop ;;
    status) do_status ;;
    *)      usage ;;
esac
