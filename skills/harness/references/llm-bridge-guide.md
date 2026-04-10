# Actor ↔ LLM bridge (`CursorAgent` + Pekko Typed)

**Goal:** Keep Pekko actor threads non-blocking while still driving the real **`agents4s.cursor.CursorAgent`** (tmux + Cursor `agent` CLI) for agentic steps.

**You do not need to open the agents4s source tree** to implement a bridge: the public API is documented below. If `CursorAgent` or `Agent` changes in a future release, update this file to match.

## agents4s API reference

All types are from **`me.anatoliikmt`** artifact **`agents4s`** (see [project-boilerplate.md](project-boilerplate.md)).

### `agents4s.Agent` (trait)

Implemented by `CursorAgent`. **`os.Path`** is Li Haoyi’s **`os-lib`** (`com.lihaoyi %% "os-lib"`).

| Member | Meaning |
|--------|---------|
| `def workspace: os.Path` | Working directory passed to the CLI (`--workspace`). |
| `def model: String` | Model name passed to the CLI (`--model`). |
| `def start(prompt: Option[String] = None): Int` | Start tmux session and optional first prompt. **Returns:** `0` success, `127` if the agent binary is missing on `PATH`, `1` on error/timeout. With `oneShot = true` and `Some(prompt)`, blocks until work completes then runs `stop()` in `finally`. |
| `def stop(interruptAttempts: Int = 10): Unit` | If busy, send interrupts; optionally kill tmux session; clean staged prompt files. |
| `def sendPrompt(text: String, timeoutS: Double = Agent.DefaultTimeoutS, promptAsFile: Boolean = true): Unit` | Wait until **ready**, then send text. If `promptAsFile = true`, writes markdown to a temp file under the workspace and sends a short “read this path” instruction (avoids huge pastes). Then waits until **busy**. |
| `def isReady: Boolean` | TUI shows the “idle / footer” state (implementation uses Cursor footer heuristics). |
| `def isBusy: Boolean` | TUI shows the agent working. |
| `def awaitReady(timeoutS: Double = …): Unit` | Block until ready or timeout (`TimeoutException`). May auto-dismiss a “Trust this workspace” prompt. |
| `def awaitBusy(timeoutS: Double = …): Unit` | Block until busy. |
| `def awaitDone(timeoutS: Double = …): Unit` | Block until no longer busy (work finished). |

**Constants:** `agents4s.Agent.DefaultTimeoutS` is `30 * 60` seconds — use it or pass an explicit `timeoutS` for faster failure in CI-like environments.

### `agents4s.cursor.CursorAgent`

Drives **`agent`** inside a **detached tmux** session (`tmux -L <socket>`).

```scala
class CursorAgent(
    val workspace: os.Path,
    val model: String,
    val tmuxSocket: String = "cursor-agent",  // tmux -L name
    val label: String = "agent",               // tmux session name
    val oneShot: Boolean = true,
    val config: agents4s.tmux.AgentConfig = agents4s.tmux.AgentConfig()
) extends agents4s.tmux.TmuxAgent
```

**CLI shape** (for mental model only): `agent --yolo --model <model> --workspace <workspace>` — you don’t assemble this yourself; `CursorAgent` does.

**Prompt staging:** when using `promptAsFile = true`, prompt bodies are written under **`workspace / ".cursor" / "prompts"`** (directory is created as needed).

### `agents4s.tmux.AgentConfig` (useful knobs)

`AgentConfig` is a case class with defaults. Common overrides:

| Field | Default | Use |
|-------|---------|-----|
| `quiet` | `false` | Set `true` in servers/actors to avoid printing attach hints to stdout. |
| `killRemoteOnStop` | `true` | Set `false` in tests that use a **mock** `Pane` and never create a real session. |
| `pollIntervalS`, `sleeper`, `clockNanos` | real time | Inject in unit tests for deterministic `CursorTuiOps` waits (agents4s tests do this). |

Import: `import agents4s.tmux.AgentConfig`

---

## Bridge pattern (recap)

1. A dedicated **`LlmBridge`** typed actor accepts **`Run(..., replyTo)`**.
2. Blocking work runs in **`Future { … }`** on Pekko’s **blocking dispatcher** (see [project-boilerplate.md](project-boilerplate.md) — config key `blocking-llm-dispatcher`).
3. Use **`context.pipeToSelf`** to get back onto the actor thread and **`replyTo !`** the typed result.

Parents send one command and wait for **`Result`** — same idea as delegating to any other child actor.

---

## Session modes

### One-shot (default)

Use when **one** prompt run is enough (most harness steps).

```scala
import agents4s.cursor.CursorAgent
import agents4s.tmux.AgentConfig

val agent = new CursorAgent(
  workspace = ws,
  model = "gpt-4.1",
  oneShot = true,
  config = AgentConfig(quiet = true)
)
val exit = agent.start(Some(fullPromptMarkdown)) // blocks until done, then stop() in finally
// exit == 0  => success; 127 => missing `agent` binary; 1 => error/timeout
```

Inside a **`Future`**, treat non-zero exit as failure; **never** call this on the actor’s default dispatcher.

### Multi-turn

Use when the spec needs **follow-up** prompts in the **same** tmux session.

```scala
val agent = new CursorAgent(ws, model, oneShot = false, config = AgentConfig(quiet = true))
try
  agent.start(None) // session up, no initial prompt
  agent.sendPrompt(firstPrompt)
  agent.awaitDone()
  agent.sendPrompt("Now write only the JSON report to /path/to/out.json")
  agent.awaitDone()
finally
  agent.stop()
```

Rules of thumb:

- After **`sendPrompt`**, wait with **`awaitDone`** before sending the next (unless the spec says otherwise).
- Always **`stop()`** when finished if `oneShot = false`, so tmux and temp prompt files are cleaned up.

---

## Prompt construction

- Load a template from **`prompts/*.md`** (resource path or `os.read`).
- Substitute **`{{PLACEHOLDER}}`** values from the message (paths, IDs, JSON snippets).
- Tell the model **exactly** which file to write (e.g. JSON under `out/` or a temp path keyed by `runId`) so the bridge can **`os.read`** and parse after **`awaitDone`**.

---

## Reading the response

After success:

1. Read the artifact path (JSON, markdown, etc.).
2. Parse in the blocking `Future` if you use Circe/ujson — add the dependency in `build.sbt`.
3. Reply with **`Ok(parsed)`** / **`Failed(reason)`** so parents can retry or escalate per spec.

If parsing fails, return a **structured** failure, not a silent default.

---

## `LlmPort` seam (production vs tests)

Define a **small port** the bridge calls so unit tests never construct **`CursorAgent`**:

```scala
import scala.concurrent.{ExecutionContext, Future}
import agents4s.Agent

trait LlmPort:
  /** Run one agentic step: start session, send prompt, wait until idle, return exit code + optional artifact text. */
  def runOneShot(
      prompt: String,
      workspace: os.Path,
      model: String,
      timeoutS: Double = Agent.DefaultTimeoutS
  )(using ExecutionContext): Future[Either[String, Option[String]]]
```

**Production** implementation: inside the `Future`, construct **`CursorAgent`**, call **`start(Some(prompt))`**, then read **`outputPath`** if needed and return `Right(Some(content))` or `Right(None)` if the contract is exit-code-only.

**Test** implementation: return **`Future.successful(Right(Some(\"{}\")))`** or **`Future.successful(Left("boom"))`** to simulate failures.

---

## Example: `LlmBridge` actor (typed, `pipeToSelf`)

Adjust package names and `LlmResult` to your harness. This version uses **raw file text** so the snippet stays valid without an extra JSON library; swap in ujson/Circe when you add that dependency.

```scala
// Example only — place under your harness package
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import agents4s.Agent

object LlmBridge:

  sealed trait Command
  final case class Run(
      promptMarkdown: String,
      workspace: os.Path,
      readOutputPath: Option[os.Path], // if Some, bridge reads file after agent completes
      model: String,
      timeoutS: Double,
      replyTo: ActorRef[Result]
  ) extends Command

  private final case class Completed(result: Result, replyTo: ActorRef[Result]) extends Command

  sealed trait Result
  final case class Ok(exitCode: Int, outputText: Option[String]) extends Result
  final case class Failed(reason: String) extends Result

  /** @param blockingEc from system.dispatchers.lookup("blocking-llm-dispatcher") */
  def apply(llm: LlmPort, blockingEc: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context =>
      given ExecutionContext = context.executionContext

      Behaviors.receiveMessage:
        case Run(prompt, workspace, outPath, model, timeoutS, replyTo) =>
          val response = llm.runOneShot(prompt, workspace, model, timeoutS)(using blockingEc).map {
            case Left(err) => Failed(err): Result
            case Right(maybeText) =>
              outPath match
                case Some(path) if os.exists(path) =>
                  Ok(0, Some(os.read(path)))
                case Some(path) =>
                  Failed(s"expected output at $path")
                case None =>
                  Ok(0, maybeText)
          }

          context.pipeToSelf(response) {
            case Success(value) => Completed(value, replyTo)
            case Failure(e)     => Completed(Failed(e.getMessage), replyTo)
          }
          Behaviors.same

        case Completed(result, replyTo) =>
          replyTo ! result
          Behaviors.same
    }
end LlmBridge
```

**Production `LlmPort`** (sketch — runs on `blockingEc` only):

```scala
import scala.concurrent.{ExecutionContext, Future}
import agents4s.cursor.CursorAgent
import agents4s.tmux.AgentConfig

final class CursorAgentLlmPort extends LlmPort:
  def runOneShot(prompt: String, workspace: os.Path, model: String, timeoutS: Double)(using
      ec: ExecutionContext
  ): Future[Either[String, Option[String]]] =
    Future {
      val agent =
        new CursorAgent(workspace, model, oneShot = true, config = AgentConfig(quiet = true))
      val code = agent.start(Some(prompt))
      if code == 127 then Left("`agent` binary not on PATH")
      else if code != 0 then Left(s"agent failed with exit code $code")
      else Right(None)
    }(using ec)
```

If the prompt instructs the model to write a file, pass **`readOutputPath = Some(...)`** in **`LlmBridge.Run`** and/or extend **`runOneShot`** to read that path inside the `Future`.

---

## Timeouts

Pass explicit **`timeoutS`** to **`awaitDone` / `awaitReady`** when you need faster failures than **`Agent.DefaultTimeoutS`**. On timeout, catch **`TimeoutException`** in the `Future` and map to **`Failed(...)`** — never leave **`replyTo`** without a response.

---

## Testing

Unit tests **must not** start real **`CursorAgent`** sessions. Inject **`LlmPort`** as in [references/testing.md](references/testing.md).

Do **not** search the filesystem for the agents4s repo when implementing a harness — use this document and [project-boilerplate.md](project-boilerplate.md) only.
