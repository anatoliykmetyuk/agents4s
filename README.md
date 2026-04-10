# agents4s (Scala)

[![CI](https://github.com/anatoliykmetyuk/cursor4s/actions/workflows/ci.yml/badge.svg)](https://github.com/anatoliykmetyuk/cursor4s/actions/workflows/ci.yml)

Scala 3 library that runs agent CLIs in tmux behind a unified API (`agents4s.Agent`). The Cursor implementation is **`agents4s.cursor.CursorAgent`**, which drives the Cursor `agent` CLI.

**Requirements:** JDK 17+, [sbt](https://www.scala-sbt.org/) 1.12.x, tmux, and `agent` on your `PATH`.

## Getting started

```bash
scripts/setup.sh
```

Example:

```scala
import os.*
import agents4s.cursor.CursorAgent
import agents4s.tmux.AgentConfig

val repo = Path("/path/to/your/repo")
val driver = new CursorAgent(repo, model = "your-model-id")

if driver.start(Some("Do one task and summarize.")) != 0 then
  sys.exit(1)

// Or keep the session for multiple prompts:
val longLived = new CursorAgent(
  repo,
  model = "your-model-id",
  oneShot = false,
  config = AgentConfig()
)
if longLived.start(None) != 0 then sys.exit(1)
longLived.sendPrompt("First instruction.")
longLived.awaitDone()
longLived.sendPrompt("Second instruction.")
longLived.awaitDone()
longLived.stop()
```

## Harness skill

This repo ships a Cursor agent skill at [`skills/harness/`](skills/harness/) for turning markdown SOPs into **Scala** harnesses that use this library.

Install (requires Node.js for `npx`):

```bash
./scripts/install-skill.sh
```

## API — `CursorAgent`

| Member | Role |
|--------|------|
| Constructor | `new CursorAgent(workspace, model, tmuxSocket = …, label = …, oneShot = …, config = AgentConfig(…))` — optional tmux socket, session label; `oneShot` controls whether `start` tears down the session in `finally`; `AgentConfig` holds `quiet`, factories, polling, and I/O streams. |
| `start` | `start(prompt: Option[String] = None): Int` — launch `agent` in tmux and set `pane`. `None`: return when the session exists. `Some`: run to completion. Returns `0`, `127` (no `agent`), or `1`. |
| `sendPrompt` | `sendPrompt(text, timeoutS = …, promptAsFile = true)` — after `start`, wait for readiness, send prompt (default: temp `.md` under `.cursor/prompts/` plus “read file” line), then wait until busy. |
| `isTrustPrompt` / `isReady` / `isBusy` | Snapshot predicates on the current pane tail (Cursor TUI). |
| `awaitReady` / `awaitBusy` / `awaitDone` | Blocking wait helpers. |
| `pane` | `Option[agents4s.tmux.Pane]` after a successful `start`. |
| `stop` | Kill tmux session (if `killRemoteOnStop` in config), delete tracked prompt files, clear `pane`. |

Shared tmux helpers: `agents4s.tmux.TmuxServer`, `TmuxPane`, `Paths`, `TmuxServer.stripAnsi`. Cursor-specific TUI detection lives in **`agents4s.cursor.CursorTuiOps`**.

## Lint & format

```bash
scripts/lint.sh
```

Auto-format: `sbt scalafmtAll`

## Tests

```bash
scripts/test.sh          # unit tests only
scripts/test.sh -i       # full suite including live agent integration
```

## License

See the repository root for license terms (match upstream project policy if forked).
