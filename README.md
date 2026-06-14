# Remote AI Terminal

A remote terminal and file manager that mirrors your AI coding sessions to any device — phone, tablet, or laptop — through a web browser. Zero client installation. Uses tmux for true screen sharing across devices.

## Why

You're running Claude Code or training a model on your main machine. You step out for lunch, attend a lecture, or your laptop runs out of battery — but your AI task is still running. Open a browser on your phone, see the exact same terminal, and keep working.

## How It Works

```
Main Machine                          Phone / Tablet
┌──────────────────┐                 ┌──────────────┐
│ tmux session     │                 │  Browser     │
│  ┌────────────┐  │    WebSocket    │  ┌────────┐  │
│  │ zsh        │◄─┼────────────────┼─►│ xterm  │  │
│  │ └─ claude  │  │  Terminal I/O   │  └────────┘  │
│  │  > code... │  │  File ops       │  ┌────────┐  │
│  └────────────┘  │                 │  │Files   │  │
│                  │                 │  └────────┘  │
└──────────────────┘                 └──────────────┘
```

- **tmux** provides a persistent PTY that multiple clients can attach to — same screen on both devices
- **Server** (Java 21 + Spring Boot + pty4j) bridges tmux to the browser over WebSocket
- **Client** (xterm.js + vanilla JS) runs in any browser, no install needed

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- tmux (`brew install tmux`)

### One command

```bash
mvn spring-boot:run
```

Then open `http://localhost:8765` in any browser, enter the token printed in the console.

### With tmux (shared terminal view)

```bash
# On your main machine
tmux new -s work
# Inside tmux, run your AI tool
claude

# In another terminal, start the server in tmux mode
TERM_TMUX_SESSION=work mvn spring-boot:run
```

Now your phone browser shows the same tmux session — same Claude Code screen, same output.

### Claude Code /remote-term skill

Place `.claude/skills/remote-term.md` in your project's `.claude/skills/` directory. Then inside any Claude Code session:

```
> /remote-term

  Remote terminal ready — tmux session: work
  Local:   http://localhost:8765
  Network: http://192.168.1.5:8765
  Token:   a1b2c3d4e5f6
```

### Build standalone JAR

```bash
mvn package -DskipTests
java -jar target/remote-ai-terminal-1.0.0.jar
```

## Remote Access

### Same network
Open `http://<machine-ip>:8765` — works out of the box.

### Across networks

Pick one (all free, no registration):

```bash
# Cloudflare Tunnel (recommended)
cloudflared tunnel --url http://localhost:8765

# bore
bore local 8765 --to bore.pub

# localtunnel
npx localtunnel --port 8765
```

Each gives a public HTTPS URL. Open it anywhere.

## Features

- **True screen sharing** — tmux attach mode: both devices see identical output, both can type
- **Real terminal** — full PTY with ANSI colors, supports vim, htop, Claude Code TUI, sudo prompts
- **File manager** — browse, view, edit, create, delete files on the server from your phone
- **Auto-reconnect** — WebSocket drops? Reconnects automatically, replays missed terminal output
- **Responsive** — works on desktop, tablet, and mobile with adaptive layout
- **Token auth** — simple token-based authentication
- **Path sandbox** — file operations restricted to workspace root with symlink-aware validation
- **Zero client install** — any browser works, PWA-ready

## Configuration

| Env var | Description | Default |
|---------|-------------|---------|
| `TERM_TOKEN` | Auth token | Random 12-char |
| `TERM_WORKSPACE` | Working directory | `$HOME` |
| `TERM_TMUX_SESSION` | tmux session name | (disabled) |

Or use CLI args:

```bash
java -jar target/remote-ai-terminal-1.0.0.jar --token mytoken --workspace /path/to/project
```

## Project Structure

```
├── pom.xml
├── src/main/java/com/remoteterm/
│   ├── App.java                          # Entry point + startup banner
│   ├── config/WebSocketConfig.java       # WebSocket endpoint config
│   ├── websocket/TerminalSocketHandler.java  # WS message routing
│   ├── terminal/PtyManager.java          # PTY lifecycle + ring buffer
│   ├── terminal/PtyOutputListener.java   # Output callback interface
│   ├── files/FileHandler.java            # File ops + security sandbox
│   ├── auth/AuthManager.java             # Token generation/validation
│   └── protocol/MessageType.java         # WS message type constants
├── src/main/resources/
│   ├── application.yml                   # Server config
│   └── static/
│       ├── index.html                    # SPA entry
│       ├── style.css                     # Dark theme + responsive
│       ├── app.js                        # WS connection + auto-reconnect
│       ├── terminal.js                   # xterm.js integration
│       └── filemanager.js                # File tree + operations
├── .claude/skills/remote-term.md         # Claude Code skill
├── 技术架构.md                            # Architecture doc (Chinese)
├── README.md
└── LICENSE
```

## WebSocket Protocol

All messages JSON over a single WebSocket connection (`/ws`):

| Message | Direction | Description |
|---------|-----------|-------------|
| `auth` | Client → Server | Token authentication |
| `term.init` | Client → Server | Initialize/resize terminal |
| `term.input` | Client → Server | Keystroke data (Base64) |
| `term.output` | Server → Client | Terminal output (Base64) |
| `term.replay` | Server → Client | History replay on reconnect |
| `term.resize` | Client → Server | Window resize event |
| `fs.list` | Bidirectional | List directory contents |
| `fs.read` | Bidirectional | Read file content |
| `fs.write` | Bidirectional | Write/create file |
| `fs.delete` | Bidirectional | Delete file or directory |
| `fs.mkdir` | Bidirectional | Create directory |

Terminal data is Base64-encoded to safely transmit raw bytes (ANSI escape sequences) over JSON.

## Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Java 21 LTS |
| Framework | Spring Boot 3.3.x |
| PTY | pty4j 0.13.x (JetBrains) |
| Build | Maven 3.9+ |
| Terminal UI | xterm.js 5.x |
| Protocol | JSON over WebSocket |
| Screen sharing | tmux |

## Security

- Token authentication required for all WebSocket connections
- File operations sandboxed to workspace root with symlink-aware path validation
- Sensitive files (`.env`, `.git/config`) flagged on read
- Files >1MB show truncated preview
- Constant-time token comparison to prevent timing attacks

## License

MIT
