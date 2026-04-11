# Actor ↔ LLM bridge (`CursorAgent` + Pekko Typed)

**Goal:** Keep Pekko actor threads non-blocking while still driving the real **`agents4s.cursor.CursorAgent`** (tmux + Cursor `agent` CLI) for agentic steps.

**You do not need to open the agents4s source tree** to implement a bridge: the public API is documented below. If `CursorAgent` or `Agent` changes in a future release, update this file to match.

## agents4s API reference

Core types are from **`me.anatoliikmt`** artifact **`agents4s-core`**; the reusable Pekko bridge is **`me.anatoliikmt` %% `agents4s-pekko`** (see [project-boilerplate.md](project-boilerplate.md)).

### `agents4s.Agent` (trait)

Implemented by `CursorAgent`. **`workspace`** is a **`java.nio.file.Path`** (JDK only; agents4s-core does not depend on os-lib).

| Member | Meaning |
|--------|---------|
| `def workspace: java.nio.file.Path` | Working directory passed to the CLI (`--workspace`). |
| `def model: String` | Model name passed to the CLI (`--model`). |
| `def start(): Unit` | Start tmux session and bind `pane`. Throws if the CLI cannot be started. |
| `def stop(): Unit` | Kill tmux session and clear state. |
| `def sendPrompt(prompt: String, promptAsFile: Boolean): Unit` | **Non-blocking:** inject keys into the pane. If `promptAsFile = true`, writes a temp markdown file and sends a short “read this path” line. Throws if not started or if already busy. |
| `def isStarted: Boolean` | Session is up. |
| `def isBusy: Boolean` | Agent is working (TUI heuristic). |
| `def isIdle: Boolean` | Default: `isStarted && !isBusy` (overridden on `CursorAgent` for Cursor footer + trust handling). |
| `def awaitStarted` / `awaitBusy` / `awaitIdle` | Blocking waits on **`scala.concurrent.duration.Duration`** (poll every1s; `TimeoutException` on expiry). |

Use **`scala.concurrent.duration.*`** imports for `30.minutes` etc.

### `agents4s.cursor.CursorAgent`

Drives **`agent`** inside a **detached tmux** session (`tmux -L <socket>`).

```scala
class CursorAgent(
    val workspace: java.nio.file.Path,
    val model: String,
    val socket: String = "cursor-agent", // tmux -L name
    val label: String = "agent"          // tmux session name
) extends agents4s.tmux.TmuxAgent
```

**CLI shape** (mental model): `agent --yolo --model <model> --workspace <workspace>` — `CursorAgent` supplies this via `startCommand`.

**Prompt staging:** when `promptAsFile = true`, prompt bodies are written to **system temp** files (not under `.cursor/prompts`).

### `agents4s.cursor.CursorTuiOps`

Low-level Cursor TUI markers and optional blocking helpers (`awaitReady`, `awaitBusy`, `awaitDone`, `handleTrust`) with an explicit **`pollIntervalMs`** (default1000). Prefer **`Agent.awaitIdle`** / **`awaitBusy`** on `CursorAgent` for normal use.

---

## Recommended: `agents4s.pekko.LlmBridge` (heartbeat actor)

Use the library **`agents4s-pekko`** for harness steps that must stay on the actor thread: **`LlmBridge[In, Out, A <: TmuxAgent]`** is an abstract class you extend once per step.

**Flow (all non-blocking on the actor thread):**

1. One actor: **`behavior`** returns **`Behavior[In | LlmBridgeTick.type]`** so timers can deliver **`LlmBridgeTick`** to the same mailbox. Send only **`In`** from your harness — **`LlmBridgeTick`** is reserved for the library heartbeat.
2. On **`start()`**, build a **`CursorAgent`** and bring tmux up.
3. On a **timer heartbeat**, when **`!agent.isBusy`**, **`agent.sendPrompt(buildPrompt(input), promptAsFile = true)`**; poll until **`isBusy`** then idle again for the main work; then send a follow-up (e.g. JSON path) with **`sendPrompt`**, **`awaitIdle`**-style polling in the bridge, **`Files.readString`**, **`parseOutput`**, **`agent.stop()`**.

Implement **`createAgent`**, **`buildPrompt`**, **`outputPath`**, **`parseOutput`**; optionally **`heartbeatInterval`** and **`followUpPrompt`**.

**Reference implementation:** **`agents4s.pekko.LLMActor`** uses a timer heartbeat, sends the input prompt only after the first idle poll, then drives JSON file read/retry — mirror that pattern if you are not using `LlmBridge` directly.

**Where replies go:** Pekko Typed has no `sender()`. Pass the destination when you spawn: **`context.spawn(GatekeeperBridge.behavior(self), "gatekeeper")`** (or a **`messageAdapter`**). **`behavior(replyTo: ActorRef[Out])`** closes over that ref for **`Out`** when a run completes.

If **`start()`** fails, the actor should **`stop()`** the agent and send **no** **`Out`**. A second **`In`** while a job is in flight is **ignored**.

---

## Session modes

### Single run then stop

```scala
import java.nio.file.Path
import scala.concurrent.duration.*

import agents4s.cursor.CursorAgent

val agent = new CursorAgent(Path.of("/work"), model = "gpt-4.1")
try
  agent.start()
  agent.awaitIdle(30.minutes)
  agent.sendPrompt(fullPromptMarkdown, promptAsFile = true)
  agent.awaitIdle(30.minutes)
finally agent.stop()
```

Inside a **`Future`**, treat exceptions as failure; **never** call this on the actor’s default dispatcher.

### Multi-turn

Use when the spec needs **follow-up** prompts in the **same** tmux session.

```scala
val agent = new CursorAgent(ws, model)
try
  agent.start()
  agent.awaitIdle(30.minutes)
  agent.sendPrompt(firstPrompt, promptAsFile = true)
  agent.awaitIdle(30.minutes)
  agent.sendPrompt("Now write only the JSON report to /path/to/out.json", promptAsFile = false)
  agent.awaitIdle(30.minutes)
finally
  agent.stop()
```

Rules of thumb:

- After **`sendPrompt`**, wait with **`awaitIdle`** before sending the next (unless the spec says otherwise).
- Always **`stop()`** when finished so tmux state is cleared.

---

## Prompt construction

- Load a template from **`prompts/*.md`** (resource path or `Files.readString`).
- Substitute **`{{PLACEHOLDER}}`** values from the message (paths, IDs, JSON snippets).
- Tell the model **exactly** which file to write (e.g. JSON under `out/` or a temp path keyed by `runId`) so the bridge can read and parse after **`awaitIdle`**.

---

## Reading the response

After success:

1. Read the artifact path (JSON, markdown, etc.).
2. Parse with uPickle/Circe (or plain string checks) inside **`parseOutput`** — add the JSON dependency in **`build.sbt`**.
3. Implement **`parseOutput(raw: String): Out`**; invalid JSON or IO issues should **`throw`** so the supervisor can handle failures, or return a domain-specific error value inside **`Out`** if your harness models failures that way.

---

## Example: per-step bridge (extend `agents4s.pekko.LlmBridge`)

```scala
import java.nio.file.Path

import agents4s.cursor.CursorAgent
import agents4s.pekko.LlmBridge
import agents4s.prompt.PromptTemplate

final case class GatekeeperIn(workspace: Path, model: String, itemId: String)
final case class GatekeeperOut(status: String, reason: Option[String])

object GatekeeperBridge extends LlmBridge[GatekeeperIn, GatekeeperOut, CursorAgent]:

  override def createAgent(in: GatekeeperIn): CursorAgent =
    new CursorAgent(in.workspace, in.model)

  override def buildPrompt(in: GatekeeperIn): String =
    val out = outputPath(in)
    PromptTemplate.load(
      "gatekeeper.md",
      Map(
        "ITEM_ID" -> in.itemId,
        "OUTPUT_JSON_PATH" -> out.toString,
      ),
    )

  override def outputPath(in: GatekeeperIn): Path =
    in.workspace.resolve("out").resolve("gatekeeper.json")

  override def parseOutput(raw: String): GatekeeperOut =
    // e.g. ujson.read(raw) ...
    GatekeeperOut("OK", None) // placeholder
```

Spawn with **`context.spawn(GatekeeperBridge.behavior(self), "gatekeeper")`** (or another **`ActorRef[GatekeeperOut]`**), then **`gatekeeper ! gatekeeperIn`**.

---

## Alternative: blocking `CursorAgent` inside a `Future`

Run **`start` / `sendPrompt` / `awaitIdle`** on a **blocking dispatcher** and **`pipeToSelf`** the result — see [project-boilerplate.md](project-boilerplate.md) (`blocking-llm-dispatcher`). **Never** call these on the actor default dispatcher.

---

## Timeouts

Pass explicit **`Duration`** to **`awaitIdle` / `awaitBusy`**. Heartbeat bridges should add a parent-level timeout if you need a hard cap.

---

## Testing

Unit tests **must not** attach to real tmux. Use **`StubAgent`** / **`ActorTestKit`** for **`LLMActor`**-style flows, or inject a **mock `Agent`**.

Do **not** search the filesystem for the agents4s repo when implementing a harness — use this document and [project-boilerplate.md](project-boilerplate.md) only.
