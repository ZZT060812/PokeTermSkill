---
name: poketerm
description: Sync your terminal to phone/tablet — monitor Claude Code sessions from any browser.
argument-hint: "[stop | status]"
---

# /poketerm

**Subcommands:**
- `/poketerm` — start server + tunnel
- `/poketerm stop` — kill tmux session, server and tunnel
- `/poketerm status` — show running state

The script lives at `$POKETERM_HOME/poketerm.sh`. Use absolute path to work from any CWD.

## Start

Run and present ONLY the connection info:

```bash
bash "$POKETERM_HOME/poketerm.sh" start
```

Then format the `=== POKETERM ===` output as:

```
PokeTerm ready — tmux: work

  Same WiFi:  http://<local-ip>:8765
  Anywhere:   <tunnel-url>
  Token:      <token>
```

## Stop

```bash
bash "$POKETERM_HOME/poketerm.sh" stop
```

## Status

```bash
bash "$POKETERM_HOME/poketerm.sh" status
```
