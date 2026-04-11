# Actor ↔ LLM bridge (`CursorAgent` + Pekko Typed)

**Goal:** Keep Pekko actor threads non-blocking while still driving the real **`agents4s.cursor.CursorAgent`** (tmux + Cursor `agent` CLI) for agentic steps.

**You do not need to open the agents4s source tree** to implement a bridge: the public API is documented below. If `CursorAgent` or `Agent` changes in a future release, update this file to match.

## agents4s API reference

Core types are from **`me.anatoliikmt`** artifact **`agents4s`**; the reusable Pekko bridge is **`me.anatoliikmt` %% `agents4s-pekko`** (see [project-boilerplate.md](project-boilerplate.md)).

### `agents4s.Agent` (trait)

Implemented by `CursorAgent`. **`os.Path`** is Li Haoyi’s **`os-lib`** (`com.lihaoyi %% "os-lib"`).

| Member | Meaning |
|--------|---------|
| `def workspace: os.Path` | Working directory passed to the CLI (`--workspace`). |
| `def model: String` | Model name passed to the CLI (`--model`). |
| `def start(prompt: Option[String] = None): Int` | Start tmux session and optional first prompt. **Returns:** `0` success, `127` if the agent binary is missing on `PATH`, `1` on error/timeout. With `oneShot = true` and `Some(prompt)`, blocks until work completes then runs `stop()` in `finally`. |
| `def stop(interruptAttempts: Int = 10): Unit` | If busy, send interrupts; optionally kill tmux session; clean staged prompt files. |
| `def sendPrompt(text: String, timeoutS: Double = Agent.DefaultTimeoutS, promptAsFile: Boolean = true): Unit` | Wait until **ready**, then send text. If `promptAsFile = true`, writes markdown to a temp file under the workspace and sends a short “read this path” instruction (avoids huge pastes). Then waits until **busy**. |
| `def firePrompt(text: String, promptAsFile: Boolean = true): Unit` | On **`TmuxAgent`**: same key injection as `sendPrompt` but **does not** call `awaitReady` / `awaitBusy`. For **`agents4s.pekko.LlmBridge`** (heartbeat actor) and other non-blocking drivers that poll `isReady` / `isBusy` themselves. |
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

## Recommended: `agents4s.pekko.LlmBridge` (heartbeat actor)

Use the library **`agents4s-pekko`** for harness steps that must stay on the actor thread: **`LlmBridge[In, Out, A <: TmuxAgent]`** is an abstract class you extend once per step.

**Flow (all non-blocking on the actor thread):**

1. One actor: **`behavior`** returns **`Behavior[In | LlmBridgeTick.type]`** so timers can deliver **`LlmBridgeTick`** to the same mailbox. Send only **`In`** from your harness — **`LlmBridgeTick`** is reserved for the library heartbeat.
2. On **`start(None)`**, build a **`CursorAgent`** (or subclass) with **`oneShot = false`** and bring tmux up.
3. On a **timer heartbeat**, poll **`isReady`**, then **`firePrompt(buildPrompt(input))`**; poll **`isBusy`** until the main work finishes; then **`firePrompt(followUpPrompt(...), promptAsFile = false)`** for JSON to **`outputPath(input)`**; poll again; **`os.read`**, **`parseOutput`**, **`agent.stop()`**.

Implement **`createAgent`**, **`buildPrompt`**, **`outputPath`**, **`parseOutput`**; optionally **`heartbeatInterval`** and **`followUpPrompt`**.

**Where replies go:** Pekko Typed has no `sender()`. Pass the destination when you spawn: **`context.spawn(GatekeeperBridge.behavior(self), "gatekeeper")`** (or a **`messageAdapter`**). **`behavior(replyTo: ActorRef[Out])`** closes over that ref for **`Out`** when a run completes.

If **`start(None)`** is non-zero, the actor stops the agent and sends **no** **`Out`**. A second **`In`** while a job is in flight is **ignored**.

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

1. Read the artifact path (JSON, markdown, etc.). **`LlmBridge`** does this after the follow-up step when **`isBusy`** is false.
2. Parse with Circe/ujson (or plain string checks) inside **`parseOutput`** — add the JSON dependency in **`build.sbt`**.
3. Implement **`parseOutput(raw: String): Out`**; invalid JSON or IO issues should **`throw`** so the supervisor can handle failures, or return a domain-specific error value inside **`Out`** if your harness models failures that way.

---

## Example: per-step bridge (extend `agents4s.pekko.LlmBridge`)

```scala
import agents4s.cursor.CursorAgent
import agents4s.pekko.LlmBridge
import agents4s.prompt.PromptTemplate
import agents4s.tmux.AgentConfig

final case class GatekeeperIn(workspace: os.Path, model: String, itemId: String)
final case class GatekeeperOut(status: String, reason: Option[String])

object GatekeeperBridge extends LlmBridge[GatekeeperIn, GatekeeperOut, CursorAgent]:

  override def createAgent(in: GatekeeperIn): CursorAgent =
    new CursorAgent(
      in.workspace,
      in.model,
      oneShot = false,
      config = AgentConfig(quiet = true),
    )

  override def buildPrompt(in: GatekeeperIn): String =
    val out = outputPath(in)
    PromptTemplate.load(
      "gatekeeper.md",
      Map(
        "ITEM_ID" -> in.itemId,
        "OUTPUT_JSON_PATH" -> out.toString,
      ),
    )

  override def outputPath(in: GatekeeperIn): os.Path =
    in.workspace / "out" / "gatekeeper.json"

  override def parseOutput(raw: String): GatekeeperOut =
    // e.g. ujson.read(raw) ...
    GatekeeperOut("OK", None) // placeholder
```

Spawn with **`context.spawn(GatekeeperBridge.behavior(self), "gatekeeper")`** (or another **`ActorRef[GatekeeperOut]`**), then **`gatekeeper ! gatekeeperIn`**.

---

## Alternative: blocking `start(Some(prompt))` inside a `Future`

If you are **not** using **`LlmBridge`**, you may still run **`CursorAgent.start(Some(prompt))`** on a **blocking dispatcher** and **`pipeToSelf`** the result — see [project-boilerplate.md](project-boilerplate.md) (`blocking-llm-dispatcher`). **Never** call **`start`/`sendPrompt`/`awaitDone`** on the actor default dispatcher.

---

## Timeouts

Pass explicit **`timeoutS`** to **`awaitDone` / `awaitReady`** when using the blocking API. The heartbeat **`LlmBridge`** does not yet enforce a global timeout; add one in a later release or wrap the step at the parent actor if needed.

---

## Testing

Unit tests **must not** attach to real tmux. Subclass **`CursorAgent`** (or **`TmuxAgent`**) with a **mock `Pane`**, override **`isReady` / `isBusy`** (and optionally **`start`**) to script the heartbeat sequence, as in **`agents4s.pekko.LlmBridgeSpec`** in the agents4s repo.

Do **not** search the filesystem for the agents4s repo when implementing a harness — use this document and [project-boilerplate.md](project-boilerplate.md) only.
