# agents4s library API (harness reference)

**Goal:** Wire **`agents4s.pekko.LLMActor`** + **`agents4s.cursor.CursorAgent`** from Pekko Typed **without** blocking the parent actor on tmux polling. You do **not** need to open the agents4s sources for normal harness work; update this file when the library API changes.

**Dependency:** `me.anatoliikmt %% "agents4s-pekko"` pulls in **`agents4s-core`**, **Pekko Typed**, **uPickle**, **upickle-jsonschema**. Tests: `me.anatoliikmt %% "agents4s-testkit" % Test`.

---

## `agents4s.Agent` (trait)

Implemented by **`CursorAgent`** and **`StubAgent`**.

| Member | Meaning |
|--------|---------|
| `def workspace: java.nio.file.Path` | Workspace root (CLI `--workspace`). |
| `def model: String` | Model id passed to the runtime. |
| `def start(): Unit` | Start session (tmux for Cursor). Throws if the CLI cannot start. |
| `def stop(): Unit` | Tear down session. |
| `def sendPrompt(prompt: String, promptAsFile: Boolean): Unit` | **Non-blocking** from caller’s perspective. If `promptAsFile = true`, writes a temp file and sends a short “read this path” line. Throws if not started or already busy. |
| `def isStarted` / `def isBusy` | Session / busy state. |
| `def isIdle: Boolean` | `isStarted && !isBusy` (default). |
| `def awaitStarted` / `awaitBusy` / `awaitIdle` | **Blocking** waits on `scala.concurrent.duration.Duration` — intended **inside** `LLMActor`’s behavior factory, **not** on the parent actor thread. |

Use **`scala.concurrent.duration.*`** for `30.minutes`, etc.

---

## `agents4s.cursor.CursorAgent`

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

## `agents4s.pekko.LLMActor`

### Object members

- `val HeartbeatTimerKey` — internal timer key.
- `case object HeartbeatTick` — timer message (only message type of the child’s `Behavior`).
- `case class LLMError(e: Exception)` — failure after retries.

### `start`

```scala
def start[O: JsonSchema: ReadWriter](
    replyTo: ActorRef[O | LLMError],
    agent: Agent,
    inputPrompt: String,
    outputInstructions: String
): Behavior[LLMActor.HeartbeatTick.type]
```

**Sequence (high level):**

1. `agent.start()`, `awaitIdle` (bounded wait), then `sendPrompt(inputPrompt, promptAsFile = true)` for the **task**.
2. **1s** heartbeat: while `agent.isBusy`, stay in behavior; when idle, send a **follow-up** prompt that asks the model to write **JSON** to a **temp file**, with auto-generated **JSON Schema** for **`O`**.
3. Read and uPickle-parse that file into **`O`**. On failure, retry the result prompt up to **3** times, then `replyTo ! LLMError(e)`.
4. On success, `replyTo ! o` and **`Behaviors.stopped`**.

**Important:** `LLMActor` **does not** call `agent.stop()`. The **parent** owns the `Agent` and should `stop()` when finished (e.g. after the child terminates — `context.watchWith`).

**Timeouts:** Heartbeat interval is fixed (**1s**); total wall-clock limits belong in the **parent** (`Behaviors.withTimers`, etc.). Initial idle wait inside `start` uses a **100s** window in the library.

**Do not** call `agent.awaitIdle` / `sendPrompt` / `start` from the **parent** actor’s `receiveMessage` thread for LLM work — spawn this child instead.

---

## Output type `O`

```scala
import upickle.default.*
import upickle.jsonschema.*

case class InspectionResult(status: String, reason: Option[String]) derives ReadWriter
given JsonSchema[InspectionResult] = JsonSchema.derived
```

- **`inputPrompt`:** Full task text (often from `PromptTemplate.load(...)`).
- **`outputInstructions`:** Short prose describing JSON fields; `LLMActor` wraps with path + schema — you do not hand-author the “write JSON to path X” prompt.

---

## `agents4s.prompt.PromptTemplate`

| API | Role |
|-----|------|
| `substitute(template, Map("KEY" -> value))` | Replace `{{KEY}}` in a string. |
| `loadResource(name)` | Load `prompts/<name>` from classpath. |
| `load(name, values)` | `loadResource` + `substitute`. |

---

## `agents4s.testkit.StubAgent` (`% Test`)

```scala
final class StubAgent(
    val workspace: java.nio.file.Path,
    val model: String = "stub-model",
    busyPhases: List[Int] = List(0, 0),
    onSendPrompt: String => Unit = _ => ()
) extends Agent
```

- **`busyPhases`:** After each `sendPrompt`, `isBusy` returns `true` for the next N heartbeat ticks per phase entry (see testkit source for exact semantics).
- **`onSendPrompt`:** Hook to simulate writes when the prompt contains the result-path cue (`"following path:"`).

Use with **`LLMActor.start[O](probe.ref, stub, ...)`** in unit tests — no tmux, no real `agent` binary.

---

## Parent integration (sketch)

```scala
import agents4s.cursor.CursorAgent
import agents4s.pekko.LLMActor
import org.apache.pekko.actor.typed.scaladsl.Behaviors

val agent = new CursorAgent(workspace, model, socket = s"run-$runId", label = "worker")
val adapter = context.messageAdapter[InspectionResult | LLMActor.LLMError] {
  case r: InspectionResult => LlmFinished(r)
  case LLMActor.LLMError(e) => LlmFailed(e)
}
val taskPrompt = PromptTemplate.load("gatekeeper.md", Map("ITEM_ID" -> itemId))
val child = context.spawn(
  LLMActor.start[InspectionResult](adapter, agent, taskPrompt, "status is OK | NEEDS_WORK | BLOCKED; reason optional"),
  s"llm-gatekeeper-$itemId"
)
context.watchWith(child, GatekeeperChildStopped(agent))
```

Handle **`GatekeeperChildStopped`** with **`agent.stop()`**.
