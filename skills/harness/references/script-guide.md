# Automation Script Guide (Scala 3)

Patterns for the main harness (`src/main/scala/.../Main.scala`). Library API: **`agents4s.cursor.CursorAgent`** and **`agents4s.tmux.AgentConfig`**.

## Working directory

Never default to the harness project root. Resolve from the SOP’s data anchor, with a `--workdir` CLI override.

```scala
import java.nio.file.Files
import os.*

def resolveWorkdir(cli: Option[String], sopDefault: Option[String]): Path =
  cli.map(Path(_)).orElse(sopDefault.map(Path(_))).getOrElse:
    val p = Files.createTempDirectory("harness-")
    System.err.println(s"No working directory specified; using: $p")
    Path(p)
```

## Configuration

```scala
val agentModel = sys.env.getOrElse("MY_HARNESS_AGENT_MODEL", "composer-2-fast")
val parallelWorkers = 5
val tmuxSocket = "my-sop-harness"
```

## Placeholder substitution

```scala
def applyPlaceholders(template: String, m: Map[String, String]): String =
  m.foldLeft(template): (acc, kv) =>
    acc.replace(s"{{${kv._1}}}", kv._2)
```

## One function per SOP step

**Mechanical** steps: pure Scala + `os.*` / stdlib.

**Agentic** steps: load template → `applyPlaceholders` → drive `CursorAgent`.

```scala
import agents4s.cursor.CursorAgent
import agents4s.tmux.AgentConfig

def runStep(entry: Item, workspace: Path, model: String, template: String, quiet: Boolean): Int =
  val prompt = applyPlaceholders(template, Map("KEY" -> entry.id.toString))
  val agent = new CursorAgent(
    workspace,
    model,
    tmuxSocket = tmuxSocket,
    label = s"step-${entry.id}",
    oneShot = true,
    config = AgentConfig(quiet = quiet)
  )
  agent.start(Some(prompt))
```

## Chunking long-running tasks

Use `oneShot = false`, then `sendPrompt` for follow-up chunks; `stop()` when finished.

```scala
val agent = new CursorAgent(
  workspace,
  model,
  tmuxSocket = tmuxSocket,
  oneShot = false,
  config = AgentConfig()
)
if agent.start(Some(applyPlaceholders(chunk0, mapping))) != 0 then return 1
agent.awaitDone()
for file <- chunks.tail do
  agent.sendPrompt(applyPlaceholders(file, mapping), promptAsFile = true)
  agent.awaitDone()
agent.stop()
```

## agents4s API

- `new CursorAgent(workspace: os.Path, model: String, tmuxSocket = …, label = …, oneShot = …, config: AgentConfig = …)`
- `def start(prompt: Option[String] = None): Int` — `0` ok, `127` no `agent` on `PATH`, `1` error/timeout.
- `def awaitDone(timeoutS: Double = agents4s.Agent.DefaultTimeoutS): Unit`
- `def sendPrompt(text: String, timeoutS: Double = …, promptAsFile: Boolean = true): Unit`
- `def isReady` / `def isBusy` / `def isTrustPrompt`
- `def awaitReady` / `def awaitBusy`
- `var pane: Option[agents4s.tmux.Pane]` — prefer high-level methods for production code.

## Parallel execution

Use `scala.concurrent.Future` with a bounded `ExecutionContext` (e.g. fixed thread pool). Give each agent a **unique** `label`. Protect shared counters with `java.util.concurrent.atomic` or `synchronized`.

## CLI

Use **mainargs**, **scopt**, or manual `args` parsing. Expose `--workdir`, `--parallel`, `--max-items`, `--only-id`, `--no-progress`.

## `main`

Keep `main` a short sequence of step calls so it mirrors the SOP outline.
