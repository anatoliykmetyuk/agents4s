# cursor-driver (Scala)

Scala 3 library that runs the Cursor `agent` CLI in tmux and exposes a small API to start the session, send prompts, and observe or wait on agent lifecycle. The main type is **`cursordriver.CursorAgent`**.

**Requirements:** JDK 17+, [sbt](https://www.scala-sbt.org/) 1.12.x, tmux, and `agent` on your `PATH`.

## Getting started

```bash
scripts/setup.sh
```

Example:

```scala
import os.*
import cursordriver.CursorAgent

val repo = Path("/path/to/your/repo")
val driver = new CursorAgent(repo, model = "your-model-id")

if driver.start(Some("Do one task and summarize.")) != 0 then
  sys.exit(1)

// Or keep the session for multiple prompts:
val longLived = new CursorAgent(repo, model = "your-model-id", killSession = false)
if longLived.start() != 0 then sys.exit(1)
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
| Constructor | `new CursorAgent(workspace, model, tmuxSocket = …, label = …, quiet = …, killSession = …, …)` — `workspace` is an `os.Path`; optional tmux socket, session label, stdout noise, and whether `start` tears the session down on exit. |
| `start` | `start(prompt: Option[String] = None): Int` — launch `agent` in tmux and set `pane`. `None`: return when the session exists. `Some`: run to completion. Returns `0`, `127` (no `agent`), or `1`. |
| `sendPrompt` | `sendPrompt(text, timeoutS = …, promptAsFile = true)` — after `start`, wait for readiness, send prompt (default: temp `.md` under `.cursor/prompts/` plus “read file” line), then wait until busy. |
| `isTrustPrompt` / `isReady` / `isBusy` | Snapshot predicates on the current pane tail. |
| `awaitReady` / `awaitBusy` / `awaitDone` | Blocking wait helpers. |
| `pane` | `Option[cursordriver.Pane]` after a successful `start`. |
| `stop` | Kill tmux session (if `killRemoteOnStop`), delete tracked prompt files, clear `pane`. |

Public attributes: `workspace`, `model`, `tmuxSocket`, `label`, `quiet`, `killSession`.

Low-level TUI helpers live in **`cursordriver.TuiOps`** (same markers and waiters as the Python package).

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
