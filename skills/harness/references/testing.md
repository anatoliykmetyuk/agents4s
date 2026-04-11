# Harness testing (Pekko Typed + ScalaTest)

Actor-system harnesses should stay **fast and deterministic** in CI: no real **`CursorAgent`**, no tmux, no live `agent` binary in unit tests.

## Principles

1. **Unit tests:** `ActorTestKit`, `BehaviorTestKit`, `TestProbe`; stub **`agents4s.Agent`** (see below).
2. **Integration tests (optional):** separate suite, gated by env var if you drive a real agent — same pattern as **`LLMActorIntegrationSpec`** in **`agents4s-pekko`**.
3. **Real filesystem** under `Files.createTempDirectory` / `java.nio.file.Path` when testing file workflows.
4. **Thin seam:** production uses **`CursorAgent`** + **`LLMActor.start`**; tests use a **`StubAgent`** (or local test double) implementing **`Agent`**.

## Layers

| Layer | Focus | Typical asserts |
|-------|--------|-----------------|
| Companion messages | `ActorName.Message` constructors | Equality, reply-to field present |
| Pure helpers | Path rules, pure parsing | Outputs for inputs |
| Actor unit | `BehaviorTestKit` / `TestProbe` | Messages sent, state transitions |
| Multi-actor | `ActorTestKit` + probes | Parent spawns child; retries capped |

## Project layout

```
<harness>/
  scripts/test.sh
  src/test/scala/<pkg>/
    OrchestratorTest.scala
    WorkerATest.scala
    LLMActorStepTest.scala   # optional: StubAgent + LLMActor.start
```

Use **`scripts/test.sh`** (runs from project root; see [project-boilerplate.md](project-boilerplate.md)).

## Patterns

| Need | Approach |
|------|-----------|
| Sync test of `Behavior` | `BehaviorTestKit` with a single actor under test |
| Async interaction | `TestProbe[A]` as `replyTo`; `probe.expectMessage` |
| Child spawned | Parent factory takes `spawn` hook or use `ActorTestKit` `spawn` |
| LLM step | Spawn **`LLMActor.start[O]`** with a **`StubAgent`**; in **`onSendPrompt`**, when the prompt contains the result-path cue (`"following path:"`), write valid JSON for **`O`** to that path (see [llm-actor-guide.md](llm-actor-guide.md)) |

## Stub `Agent` + `LLMActor` (concrete)

**`StubAgent`** is not published on the main classpath — copy it from **`agents4s-pekko`** test sources (`agents4s-pekko/src/test/scala/agents4s/pekko/StubAgent.scala`) into your harness under e.g. **`src/test/scala/.../StubAgent.scala`**, or paste the class below. **`busyPhases`** controls how many heartbeat ticks **`isBusy`** returns true after each **`sendPrompt`**.

```scala
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.duration.*

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import agents4s.Agent
import agents4s.pekko.LLMActor

import upickle.default.*
import upickle.jsonschema.*

/** Copy into src/test; mirrors agents4s-pekko test support. */
final class StubAgent(
    val workspace: java.nio.file.Path,
    val model: String = "stub-model",
    busyPhases: List[Int] = List(0, 0),
    onSendPrompt: String => Unit = _ => ()
) extends Agent:

  private var phaseIdx = 0
  private var busyRemaining: Int = busyPhases.headOption.getOrElse(0)
  @volatile private var started = false

  override def start(): Unit =
    started = true
    phaseIdx = 0
    busyRemaining = busyPhases.lift(0).getOrElse(0)

  override def stop(): Unit =
    started = false

  override def sendPrompt(text: String, promptAsFile: Boolean): Unit =
    if !started then throw new RuntimeException("StubAgent is not started; call start() first")
    if busyRemaining > 0 then
      throw new RuntimeException("StubAgent is busy; wait for idle before sendPrompt")
    onSendPrompt(text)
    phaseIdx += 1
    busyRemaining = busyPhases.lift(phaseIdx).getOrElse(0)

  override def isStarted: Boolean = started

  override def isBusy: Boolean =
    if !started then throw new RuntimeException("StubAgent is not started; call start() first")
    if busyRemaining > 0 then
      busyRemaining -= 1
      true
    else false

class GatekeeperLlmStepTest extends AnyFunSuite with Matchers:

  case class GatekeeperOut(status: String, reason: Option[String]) derives ReadWriter
  given JsonSchema[GatekeeperOut] = JsonSchema.derived

  private val jsonPathPattern = java.util.regex.Pattern.compile(
    "following path:\\s*(.+)\\s*",
    java.util.regex.Pattern.MULTILINE
  )

  private def extractJsonPath(prompt: String): java.nio.file.Path =
    val m = jsonPathPattern.matcher(prompt)
    m.find() shouldBe true
    java.nio.file.Path.of(m.group(1).trim)

  test("LLMActor returns parsed output with StubAgent") {
    val kit = ActorTestKit()
    try
      val ws = Files.createTempDirectory("gatekeeper-test")
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0),
        onSendPrompt = p =>
          if p.contains("following path:") then
            val path = extractJsonPath(p)
            Files.writeString(
              path,
              """{"status":"OK","reason":null}""",
              StandardCharsets.UTF_8
            )
      )
      val probe = kit.createTestProbe[GatekeeperOut | LLMActor.LLMError]()
      val child = kit.spawn(
        LLMActor.start[GatekeeperOut](
          probe.ref,
          stub,
          "task body",
          "status is OK | NEEDS_WORK | BLOCKED; reason optional"
        )
      )
      probe.expectMessage(30.seconds, GatekeeperOut("OK", None))
      probe.expectTerminated(child, 5.seconds)
    finally kit.shutdownTestKit()
  }
```

No **`blocking-llm-dispatcher`** is required for this pattern.

## Shared fixtures

- One `ActorTestKit` per test class (`afterAll`: `testKit.shutdownTestKit()`).
- Reuse a helper `def tmpWorkspace: java.nio.file.Path` for workspace-root tests.
- Keep **prompt templates** under `prompts/` in fixture dirs only if assertions need template text.

## Checklist

- [ ] ScalaTest in **Test** scope; `Test / fork := true` if using `ActorSystem`.
- [ ] No real `CursorAgent` / tmux in default `test`.
- [ ] Cover bounded-retry and early-exit branches from `specs/`.
- [ ] `./scripts/test.sh` passes before handoff.
