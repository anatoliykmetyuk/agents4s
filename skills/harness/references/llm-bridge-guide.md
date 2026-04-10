# Actor ↔ LLM bridge (`CursorAgent` + Pekko Typed)

**Goal:** Keep Pekko actor threads non-blocking while still driving the real **`agents4s.cursor.CursorAgent`** (tmux + Cursor CLI) for agentic steps.

## Pattern

1. A dedicated **`LlmBridge`** typed actor (or small trait + implementation) accepts a **command** such as `Run(promptText, workspace, outputPath, replyTo: ActorRef[Result])`.
2. The bridge runs blocking work inside `Future { ... }` scheduled on Pekko’s **blocking dispatcher** (see `application.conf` in [project-boilerplate.md](project-boilerplate.md)).
3. On success or failure, the bridge uses `context.pipeToSelf` to wrap the outcome in an **internal** message and then `replyTo ! ...`.

Parent actors send **one bridge command** and forget until `Result` arrives — this mirrors “return immediately, resume on subactor response” from the actor harness design.

## Prompt construction

- Load a template from `prompts/*.md`.
- Substitute `{{PLACEHOLDER}}` values from the Scala message (paths, IDs, JSON snippets).
- Include **explicit instructions** for the model to write **machine-readable output** (recommended: **JSON** matching a schema) to a **known path** under `out/` or a temp dir derived from `runId`.

## Session shape

- **Single shot:** `oneShot = true` on `CursorAgent` when one prompt is enough; still wait for idle via `awaitDone`.
- **Multi-turn:** `oneShot = false`, initial `start(Some(mainPrompt))`, then `sendPrompt` for follow-ups (e.g. “now write only the JSON report to path X”), `awaitDone` between steps, `stop()` when finished.

## Reading the response

After the agent returns to **idle**, the bridge:

1. Reads the JSON (or markdown) file.
2. Parses into a Scala value (e.g. `io.circe` or `ujson` — add the dependency in `build.sbt` if you choose to parse in the bridge).
3. Sends a **typed** result ADT (`Success(payload)` / `Failure(reason)`) to `replyTo`.

If parsing fails, reply with a structured failure so the parent can `PortFailed` / `Rejected` / retry per spec.

## Timeouts

Reuse `agents4s.Agent.DefaultTimeoutS` or pass explicit `timeoutS` to `awaitDone` / `awaitReady`. On timeout, complete the `Future` with failure and map to a message — do not leave the parent hanging.

## Testing

Unit tests **must not** start real `CursorAgent` sessions. Inject a `LlmPort` trait:

```scala
trait LlmPort:
  def run(prompt: String, workspace: os.Path): scala.concurrent.Future[LlmResult]
```

Production uses `CursorAgent`; tests use a stub returning canned `LlmResult`.
