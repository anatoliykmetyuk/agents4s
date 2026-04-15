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

After the library exhausts its own JSON parse retries, **`LLMActor` throws** and the **spawned child fails**; **`replyTo` is only used on success** (**`O`**). There is no failure message on **`replyTo`**.

### `start`

```scala
def start[O: JsonSchema: ReadWriter](
    replyTo: ActorRef[O],
    agentConstructor: () => Agent,
    inputPrompt: String,
    outputInstructions: String
)
```

Returns a **`Behavior`** to **`spawn`**; on success it **`tell`**s **`O`** to **`replyTo`** then stops. On unrecoverable failure the child actor fails (use **`watch`** / **`Terminated`** or supervision to react).

**What it does (high level):** constructs an **`Agent`** via **`agentConstructor`**, runs the **task** from **`inputPrompt`**, then asks the model for **structured JSON** matching **`O`** (schema + instructions come from **`outputInstructions`** and the library). Parses the result and **`tell`**s **`replyTo`**, then stops.

**Lifecycle:** `LLMActor` **owns** the constructed agent and calls **`agent.stop()`** on **`PostStop`** (normal completion, failure, or **`context.stop`** on the child). The parent does **not** need to **`stop()`** the agent when the child terminates.

**Do not** call **`agent.awaitIdle`** / **`sendPrompt`** / **`start`** from the **parent**’s **`receiveMessage`** for LLM work — spawn this child instead. For **overall** deadlines or cancellation, use the **parent** (timers, `context.stop`, etc.).

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

Templates are files under **`src/main/resources/prompts/`** in the harness project; at runtime they appear on the classpath as **`prompts/<name>`** (e.g. `src/main/resources/prompts/inspect.md` → load with **`load("inspect.md", ...)`**).

| API | Role |
|-----|------|
| `substitute(template, Map("KEY" -> value))` | Replace `{{KEY}}` in a string. |
| `loadResource(name)` | Load bytes from classpath resource **`prompts/<name>`** (UTF-8). |
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

- **`busyPhases`:** Controls how **`StubAgent`** simulates **`isBusy`** after each **`sendPrompt`** in tests (see testkit source for exact semantics).
- **`onSendPrompt`:** Hook to simulate writes when the prompt contains the result-path cue (`"following path:"`).

Use with **`LLMActor.start[O](probe.ref, () => stub, ...)`** in unit tests — no tmux, no real `agent` binary.

---

## Parent integration (sketch)

```scala
import agents4s.cursor.CursorAgent
import agents4s.pekko.LLMActor
import org.apache.pekko.actor.typed.scaladsl.Behaviors

val adapter = context.messageAdapter[InspectionResult](LlmFinished.apply)
val taskPrompt = PromptTemplate.load("gatekeeper.md", Map("ITEM_ID" -> itemId))
val child = context.spawn(
  LLMActor.start[InspectionResult](
    adapter,
    () => new CursorAgent(workspace, model, socket = s"run-$runId", label = "worker"),
    taskPrompt,
    "status is OK | NEEDS_WORK | BLOCKED; reason optional"
  ),
  s"llm-gatekeeper-$itemId"
)
context.watch(child)
```

Use **`context.watch(child)`** so the parent can handle **`Terminated`** when the child stops—both after a successful reply and when the child **fails** after retries (no error is sent on **`replyTo`**). Teardown of the Cursor session is handled inside **`LLMActor`**.
