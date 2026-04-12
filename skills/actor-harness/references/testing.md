# Actor-harness testing (Pekko Typed + ScalaTest)

Actor-system harnesses should stay **fast and deterministic** in CI: no real **`CursorAgent`**, no tmux, no live `agent` binary in unit tests.

## Principles

1. **Unit tests:** `ActorTestKit`, `BehaviorTestKit`, `TestProbe`; stub **`agents4s.Agent`** with **`agents4s.testkit.StubAgent`** (see below).
2. **Integration tests (optional):** separate suite, gated by env var if you drive a real agent — same pattern as **`LLMActorIntegrationSpec`** in **`agents4s-pekko`**.
3. **Real filesystem** under `Files.createTempDirectory` / `java.nio.file.Path` when testing file workflows.
4. **Thin seam:** production uses **`CursorAgent`** + **`LLMActor.start`**; tests use **`StubAgent`** from **`me.anatoliikmt` %% `agents4s-testkit`** (`% Test`).

## Layers

| Layer | Focus | Typical asserts |
|-------|--------|-----------------|
| Message types | `ActorName` case class constructors | Equality, `replyTo` present |
| Pure helpers | Path rules, pure parsing | Outputs for inputs |
| Actor unit | `BehaviorTestKit` / `TestProbe` | Messages sent, state transitions |
| Multi-actor | `ActorTestKit` + probes | Parent spawns child; retries capped |

**Union `AcceptedMessages`:** use **`TestProbe[A | B | C]`** (or your full union) so **`expectMessage`** matches the same types the actor emits.

## Project layout

```
<harness>/
  scripts/test.sh
  src/test/scala/<pkg>/
    GetItPassingTest.scala
    LLMActorStepTest.scala   # optional: StubAgent + LLMActor.start
```

Use **`scripts/test.sh`** (runs from project root; see [project-boilerplate.md](project-boilerplate.md)).

## Patterns

| Need | Approach |
|------|-----------|
| Sync test of `Behavior` | `BehaviorTestKit` with a single actor under test |
| Async interaction | `TestProbe[AcceptedMessages]` as `replyTo`; `probe.expectMessage` |
| Child spawned | Parent factory takes `spawn` hook or use `ActorTestKit` `spawn` |
| LLM step | Spawn **`LLMActor.start[O]`** with **`StubAgent`**; in **`onSendPrompt`**, when the prompt contains the result-path cue (`"following path:"`), write valid JSON for **`O`** to that path (see [library-api.md](library-api.md)) |

## `StubAgent` + `LLMActor` (concrete)

Add **`"me.anatoliikmt" %% "agents4s-testkit" % "<version>" % Test`** (see [project-boilerplate.md](project-boilerplate.md)). **`busyPhases`** controls how many heartbeat ticks **`isBusy`** returns true after each **`sendPrompt`**.

```scala
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.duration.*

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import agents4s.pekko.LLMActor
import agents4s.testkit.StubAgent

import upickle.default.*
import upickle.jsonschema.*

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
- Keep **prompt templates** under `src/test/resources/prompts/` (classpath `prompts/…`) when tests need real **`PromptTemplate.load`** behavior; otherwise stub the task string.

## Checklist

- [ ] ScalaTest in **Test** scope; `Test / fork := true` if using `ActorSystem`.
- [ ] No real `CursorAgent` / tmux in default `test`.
- [ ] Cover bounded-retry and early-exit branches from `specs/`.
- [ ] `./scripts/test.sh` passes before handoff.
