# LLM steps with `LLMActor` (`agents4s-pekko`)

**Goal:** Drive **`agents4s.Agent`** (typically **`CursorAgent`**: tmux + Cursor `agent` CLI) from Pekko Typed **without** blocking the parent actor’s thread on TUI polling. Use the library **`agents4s.pekko.LLMActor`**.

**You do not need to open the agents4s source tree** to wire a harness: the public API is summarized here. When `Agent`, `CursorAgent`, or `LLMActor` change in a release, update this file to match.

## Dependency

Harness projects depend on **`me.anatoliikmt` %% `agents4s-pekko`** (see [project-boilerplate.md](project-boilerplate.md)). That artifact pulls in **`agents4s-core`**, **Pekko Typed**, **uPickle**, and **upickle-jsonschema**.

## `agents4s.Agent` (trait)

Implemented by `CursorAgent`. **`workspace`** is **`java.nio.file.Path`** (JDK only).

| Member | Meaning |
|--------|---------|
| `def workspace: java.nio.file.Path` | Working directory passed to the CLI (`--workspace`). |
| `def model: String` | Model name passed to the CLI (`--model`). |
| `def start(): Unit` | Start tmux session. Throws if the CLI cannot be started. |
| `def stop(): Unit` | Tear down session. |
| `def sendPrompt(prompt: String, promptAsFile: Boolean): Unit` | **Non-blocking** from the caller’s perspective: inject keys into the pane. If `promptAsFile = true`, writes a temp markdown file and sends a short “read this path” line. Throws if not started or if already busy. |
| `def isStarted` / `isBusy` / `isIdle` | Session and TUI-derived busy/idle (see `CursorAgent`). |
| `awaitStarted` / `awaitBusy` / `awaitIdle` | **Blocking** waits on **`Duration`** — these run **inside** `LLMActor`’s behavior factory, not on the parent actor thread. |

Use **`scala.concurrent.duration.*`** for `30.minutes`, etc.

### `agents4s.cursor.CursorAgent`

```scala
class CursorAgent(
    val workspace: java.nio.file.Path,
    val model: String,
    val socket: String = "cursor-agent", // tmux -L name
    val label: String = "agent"          // tmux session name
) extends agents4s.tmux.TmuxAgent
```

**CLI:** `agent --yolo --model <model> --workspace <workspace>`.

---

## `LLMActor.start` — the standard harness pattern

**Signature:**

```scala
def start[O: JsonSchema: ReadWriter](
    replyTo: ActorRef[O | LLMError],
    agent: Agent,
    inputPrompt: String,
    outputInstructions: String
): Behavior[LLMActor.HeartbeatTick.type]
```

**What it does (high level):**

1. **`agent.start()`**, **`awaitIdle`**, then **`sendPrompt(inputPrompt, promptAsFile = true)`** for the **task** (your spec’s agentic work).
2. Uses a **1s heartbeat timer** (`LLMActor.HeartbeatTick`): while **`agent.isBusy`**, stay in the same behavior; when idle, sends a **follow-up prompt** that tells the model to write **JSON** to a **temp file**, including an auto-generated **JSON Schema** for **`O`**.
3. Reads and **uPickle**-parses that file into **`O`**. On parse/IO failure, **retries** the result prompt up to **3** times, then **`replyTo ! LLMError(e)`**.
4. On success, **`replyTo ! o`** and **`Behaviors.stopped`**.

**Important:** `LLMActor` **does not** call **`agent.stop()`**. The **parent** owns the `Agent` instance and should **`stop()`** it when the run is finished (e.g. after the child terminates — use **`context.watchWith`** or equivalent).

### Output type `O`

`O` must have **uPickle** **`ReadWriter`** and **`JsonSchema`** (Scala3 derives):

```scala
import upickle.default.*
import upickle.jsonschema.*

case class InspectionResult(status: String, reason: Option[String]) derives ReadWriter
given JsonSchema[InspectionResult] = JsonSchema.derived
```

- **`inputPrompt`**: full task text (often from **`PromptTemplate.load("inspect.md", Map(...))`**).
- **`outputInstructions`**: short prose describing what belongs in each JSON field. `LLMActor` wraps this with the result path and schema; you **do not** hand-write the “write JSON to path X” prompt for the **result** step.

---

## Parent actor integration

Spawn a **child** with `LLMActor.start` and a **`messageAdapter`** (or `ActorRef` you already hold) so **`O | LLMError`** maps into the parent **`Command`**:

```scala
import agents4s.cursor.CursorAgent
import agents4s.pekko.LLMActor
import org.apache.pekko.actor.typed.scaladsl.Behaviors

// Inside Behaviors.setup { context => ...

val agent = new CursorAgent(workspace, model, socket = s"run-$runId", label = "worker")
val adapter = context.messageAdapter[InspectionResult | LLMActor.LLMError] {
  case r: InspectionResult => LlmFinished(r)
  case LLMActor.LLMError(e) => LlmFailed(e)
}
val taskPrompt = PromptTemplate.load("gatekeeper.md", Map("ITEM_ID" -> itemId, ...))
val child = context.spawn(
  LLMActor.start[InspectionResult](adapter, agent, taskPrompt, "status is OK | NEEDS_WORK | BLOCKED; reason optional"),
  s"llm-gatekeeper-$itemId"
)
context.watchWith(child, GatekeeperChildStopped(agent))
```

Handle **`GatekeeperChildStopped`** by **`agent.stop()`** (and clear bookkeeping).

### Timeouts

- `LLMActor` uses a fixed **1s** heartbeat; hard caps on **total** wall time should be enforced by the **parent** (`Behaviors.withTimers`, `ask` with timeout, etc.).
- For **blocking** waits inside `LLMActor`’s initializer (`awaitIdle` after `start`), the implementation uses a **100s** window; adjust library or wrap in your own behavior if you need different defaults.

### Multi-turn task in one run

Put everything the model must do **before** the structured JSON step into **`inputPrompt`** (one markdown prompt as file). `LLMActor` always adds **one** automatic **result** prompt after the task is idle. If the spec truly needs **extra** interactive rounds beyond that, extend the library or add a dedicated child protocol — do **not** call **`agent.awaitIdle`** / **`sendPrompt`** from the parent actor thread.

---

## Prompts under `prompts/`

- **Task templates** (`prompts/*.md`): procedure, context tables, **`{{TOKENS}}`** via **`PromptTemplate.load` / `substitute`** — same as mechanical harness docs.
- **Structured output:** describe field meanings in **`outputInstructions`**; **do not** duplicate the JSON path/schema wording — **`LLMActor`** injects those.

---

## `agents4s.prompt.PromptTemplate`

- **`substitute(template, Map("KEY" -> value))`** — replaces **`{{KEY}}`**.
- **`loadResource` / `load`** — reads **`prompts/<name>`** from the classpath.

---

## Testing

Unit tests **must not** start real tmux. Implement **`agents4s.Agent`** with a **stub** (see **`StubAgent`** in **`agents4s-pekko`** tests: `agents4s-pekko/src/test/scala/agents4s/pekko/StubAgent.scala`) or copy a minimal stub into your harness test sources.

**Pattern:** `ActorTestKit`, **`LLMActor.start[O](probe.ref, stub, ...)`**, **`onSendPrompt`** detects the result prompt (e.g. contains `"following path:"`), writes valid JSON to that path. **`busyPhases`** simulates idle/busy ticks so the heartbeat logic is exercised. See **`LLMActorSpec`** in the same module.

Integration tests (live `agent` + tmux) should stay **gated** by an env var, same as **`LLMActorIntegrationSpec`**.

---

## When agents4s changes

Update this guide (and [project-boilerplate.md](project-boilerplate.md)) so harness authors do not need to search the repo. Prefer **`LLMActor`** over ad-hoc **`Future`** + **`pipeToSelf`** + blocking dispatchers unless you have a requirement the library does not cover.
