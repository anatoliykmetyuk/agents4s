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
import java.nio.file.Path
import scala.concurrent.duration.*

import agents4s.cursor.CursorAgent

val repo = Path.of("/path/to/your/repo")
val driver = new CursorAgent(repo, model = "your-model-id")

driver.start()
driver.sendPrompt("Do one task and summarize.", promptAsFile = true)
driver.awaitIdle(30.minutes)

// Multi-turn session:
val longLived = new CursorAgent(repo, model = "your-model-id")
longLived.start()
longLived.awaitIdle(30.minutes)
longLived.sendPrompt("First instruction.", promptAsFile = true)
longLived.awaitIdle(30.minutes)
longLived.sendPrompt("Second instruction.", promptAsFile = true)
longLived.awaitIdle(30.minutes)
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
| Constructor | `new CursorAgent(workspace: java.nio.file.Path, model: String, socket = "cursor-agent", label = "agent")` — workspace root, model id, optional tmux `-L` socket name and session name. |
| `start` | `start(): Unit` — launch `agent` in a detached tmux session and bind `pane`. |
| `sendPrompt` | `sendPrompt(prompt, promptAsFile)` — non-blocking; with `promptAsFile = true`, writes a temp `.md` and sends a “read file” line. |
| `isTrustPrompt` / `isIdle` / `isBusy` | Snapshot predicates on the current pane tail (Cursor TUI). |
| `awaitStarted` / `awaitBusy` / `awaitIdle` | Blocking wait helpers from `Agent` (1s polling). |
| `pane` | Live `agents4s.tmux.Pane` after `start()` until `stop()`. |
| `stop` | Kill the tmux session and clear state. |

Shared tmux helpers: `agents4s.tmux.TmuxServer`, `TmuxPane`, `Paths`, `TmuxServer.stripAnsi`. Cursor-specific TUI detection lives in **`agents4s.cursor.CursorTuiOps`** (optional `pollIntervalMs` on await helpers).

## Lint & format

```bash
scripts/lint.sh
```

Auto-format: `sbt scalafmtAll`

## Tests

```bash
scripts/test.sh          # full suite (integration runs when agent + tmux available)
scripts/test.sh -u       # unit tests only (skip integration)
```

## License

See the repository root for license terms (match upstream project policy if forked).
