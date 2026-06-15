# PokeTerm

A Claude Code skill that streams your terminal session to any browser via WebSocket. Built on tmux + pty4j + xterm.js.

## Overview

`/poketerm` starts a Java server on your machine that attaches to a tmux session and bridges it to a browser over WebSocket. The browser renders a full terminal (xterm.js) and a file tree. Any device with a browser can view and interact with the terminal — phone, tablet, or another laptop.

## Architecture

```
┌── tmux session "work" ──────────────────────────┐
│  /dev/ttys00X (PTY)                             │
│  ┌──────────────────────────────────────────┐   │
│  │ zsh                                      │   │
│  │  └─ claude (or any interactive program)  │   │
│  └──────────────────────────────────────────┘   │
│        ▲ stdin           │ stdout ▼             │
│        │                 │                      │
│  ┌─────┴─────────────────┴──────┐               │
│  │ Terminal.app    PtyManager   │               │
│  │ (local)         (remote)     │               │
│  └──────────────────────────────┘               │
└─────────────────────────────────────────────────┘
                         │
                    WebSocket
                    JSON frames
                         │
               ┌─────────▼──────────┐
               │  Browser           │
               │  ┌──────────────┐  │
               │  │ xterm.js     │  │
               │  │ file tree    │  │
               │  │ (vanilla JS) │  │
               │  └──────────────┘  │
               └────────────────────┘
```

The key insight: instead of trying to read another process's terminal buffer (requires root/ptrace on macOS), we use tmux as a shared PTY. The server runs `tmux attach -t <session>`, which connects to the same PTY that the local Terminal.app is attached to. Both see identical output and can send input.

## Why tmux

| Approach | Trade-off |
|----------|-----------|
| Spawn new shell | Independent session, doesn't see existing Claude Code output |
| Read `/dev/ttys00X` | Requires root / `ptrace`, blocked by SIP on macOS |
| **tmux attach** | Shared PTY, both clients see same screen, no privilege needed |

The skill auto-detects `$TMUX`. If the user is already in tmux, it uses that session. If not, it creates one (`tmux new-session -d -s work`) and attaches to it. Running `claude --resume` inside the tmux session restores the conversation.

## Terminal streaming

The server spawns `tmux attach` through pty4j, which creates a fresh PTY whose master side is controlled by the Java process. A virtual thread loops reading from the PTY's stdout:

```java
// PtyManager.java
Thread.ofVirtual().name("pty-reader").start(() -> {
    byte[] buf = new byte[8192];
    while ((n = pty.getInputStream().read(buf)) != -1) {
        // Push to ring buffer (replay on reconnect)
        // Broadcast Base64-encoded to all WebSocket sessions
    }
});
```

Terminal output is raw bytes (ANSI escape codes, control chars). JSON can't carry raw bytes, so each chunk is Base64-encoded before sending:

```json
{"type": "term.output", "data": "G1szM20SGVsbG8..."}
```

Keystrokes flow the other way: xterm.js `onData` → Base64 encode → WebSocket → write to PTY stdin. Full duplex.

## Ring buffer & reconnection

The server keeps the last 500 output chunks (≈ 4MB max) in a ring buffer. On reconnect, the client sends `term.init` with `fresh: false`, and the server replays the buffer before resuming live output. This covers network switches (WiFi ↔ cellular), screen sleep, and browser backgrounding.

## File manager

File operations share the same WebSocket connection. The protocol:

```
→ {"type": "fs.list", "path": "."}
← {"type": "fs.list", "files": [{name, isDir, size, mtime}]}

→ {"type": "fs.read", "path": "..."}
← {"type": "fs.read", "content": "...", truncated: false}

→ {"type": "fs.write", "path": "...", "content": "..."}
← {"type": "fs.result", "ok": true}
```

All paths are resolved through `toRealPath()` and checked against the workspace root to prevent directory traversal. Non-existent paths (for mkdir / new file write) are validated via normalized path prefix.

## WebSocket protocol

Single connection at `/ws`. All messages are JSON with a `type` field:

| type | dir | payload |
|------|-----|---------|
| `auth` | → | `{token}` |
| `term.init` | → | `{cols, rows, fresh}` |
| `term.input` | → | `{data: base64}` |
| `term.output` | ← | `{data: base64}` |
| `term.replay` | ← | `{data: base64}` (reconnect only) |
| `term.resize` | → | `{cols, rows}` |
| `fs.list / read / write / delete / mkdir` | ↔ | path-based |

## Start

Requirements: Java 21, Maven 3.9, tmux.

```bash
git clone git@github.com:ZZT060812/PokeTerm.git
cp -r PokeTerm/.claude/skills/poketerm/ your-project/.claude/skills/
```

Then in Claude Code:

```
> /poketerm
```

## Tech stack

- **Server**: Java 21, Spring Boot 3.3, pty4j 0.13, embedded Tomcat
- **Screen sharing**: tmux (multi-client PTY)
- **Frontend**: xterm.js 5.x, vanilla JS (zero framework)
- **Protocol**: JSON over WebSocket, Base64 for binary terminal data
- **Auth**: constant-time token comparison, token via CLI arg / env var / auto-generate
- **File safety**: `toRealPath()` prefix check, sensitive file detection, 1MB read cap

## License

MIT
