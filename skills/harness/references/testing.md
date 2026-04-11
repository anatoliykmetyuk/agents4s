# Harness testing (Pekko Typed + ScalaTest)

Actor-system harnesses should stay **fast and deterministic** in CI: no real **`CursorAgent`**, no tmux, no live `agent` binary in unit tests.

## Principles

1. **Unit tests:** `ActorTestKit`, `BehaviorTestKit`, `TestProbe`; **mock** the LLM port.
2. **Integration tests (optional):** separate suite, gated by env var if you ever drive a real agent—same pattern as agents4s integration tests.
3. **Real filesystem** under `Files.createTempDirectory` / `java.nio.file.Path` when testing file workflows.
4. **Thin seams:** define a small `LlmPort` (or pass a stub `Behavior`) so production uses `LlmBridge` + `CursorAgent` and tests return canned results.

## Layers

| Layer | Focus | Typical asserts |
|-------|--------|-----------------|
| Companion messages | `ActorName.Message` constructors | Equality, reply-to field present |
| Pure helpers | JSON parsing, path rules | Outputs for inputs |
| Actor unit | `BehaviorTestKit` / `TestProbe` | Messages sent, state transitions |
| Multi-actor | `ActorTestKit` + probes | Parent spawns child; retries capped |

## Project layout

```
<harness>/
  scripts/test.sh
  src/test/scala/<pkg>/
    OrchestratorTest.scala
    WorkerATest.scala
    LlmBridgeTest.scala        # optional, with stub only
```

Use **`scripts/test.sh`** (runs from project root; see [project-boilerplate.md](project-boilerplate.md)).

## Patterns

| Need | Approach |
|------|-----------|
| Sync test of `Behavior` | `BehaviorTestKit` with a single actor under test |
| Async interaction | `TestProbe[A]` as `replyTo`; `probe.expectMessage` |
| Child spawned | Parent factory takes `spawn` hook or use `ActorTestKit` `spawn` |
| Blocking LLM | Never call real `CursorAgent`—inject `LlmPort` returning `Future.successful(...)` |

## Mock `LlmPort` + `LlmBridge` (concrete)

Match the **`LlmPort`** shape from [llm-bridge-guide.md](llm-bridge-guide.md). Production uses **`CursorAgentLlmPort`**; tests use a stub that never touches tmux:

```scala
import scala.concurrent.{ExecutionContext, Future}

final class MockLlmPort(
    result: Either[String, Option[String]] = Right(Some("{\"status\":\"OK\"}"))
) extends LlmPort:
  def runOneShot(prompt: String, workspace: java.nio.file.Path, model: String, timeoutS: Double)(using
      ec: ExecutionContext
  ): Future[Either[String, Option[String]]] =
    Future.successful(result)
```

**Actor test** (ScalaTest + `ActorTestKit`): spawn **`LlmBridge`** with the mock and assert on **`replyTo`**. Use the same **`blocking-llm-dispatcher`** from `application.conf` as production so behavior matches.

```scala
import java.nio.file.Files

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.DispatcherSelector
import org.scalatest.wordspec.AnyWordSpec

class LlmBridgeTest extends ScalaTestWithActorTestKit with AnyWordSpec:

  private def blockingEc =
    system.dispatchers.lookup(DispatcherSelector.fromConfig("blocking-llm-dispatcher"))

  private def tmpWorkspace: java.nio.file.Path = Files.createTempDirectory("llm-bridge-test")

  "LlmBridge" should {
    "reply Ok when the port succeeds" in {
      val probe = createTestProbe[LlmBridge.Result]()
      val ws = tmpWorkspace
      val bridge = spawn(LlmBridge(new MockLlmPort(Right(Some("artifact"))), blockingEc))
      bridge ! LlmBridge.Run(
        promptMarkdown = "ignored-by-mock",
        workspace = ws,
        readOutputPath = None,
        model = "noop",
        timeoutS = 30 * 60,
        replyTo = probe.ref
      )
      probe.expectMessage(LlmBridge.Ok(0, Some("artifact")))
    }

    "reply Failed when the port returns Left" in {
      val probe = createTestProbe[LlmBridge.Result]()
      val ws = tmpWorkspace
      val bridge = spawn(LlmBridge(new MockLlmPort(Left("boom")), blockingEc))
      bridge ! LlmBridge.Run("p", ws, None, "m", 30 * 60, probe.ref)
      probe.expectMessage(LlmBridge.Failed("boom"))
    }
  }
```

If you don’t load **`application.conf`** in tests, either merge a `application-test.conf` that defines **`blocking-llm-dispatcher`**, or inject a harmless **`ExecutionContext`** (e.g. `system.dispatchers.lookup(DispatcherSelector.default())`) **only** when the mock always returns `Future.successful` and never blocks—prefer aligning test config with prod.

## Shared fixtures

- One `ActorTestKit` per test class (`afterAll`: `testKit.shutdownTestKit()`).
- Reuse a helper `def tmpWorkspace: java.nio.file.Path` for workspace-root tests.
- Keep **prompt templates** under `prompts/` in fixture dirs only if assertions need template text.

## Checklist

- [ ] ScalaTest in **Test** scope; `Test / fork := true` if using `ActorSystem`.
- [ ] No real `CursorAgent` / tmux in default `test`.
- [ ] Cover bounded-retry and early-exit branches from `specs/`.
- [ ] `./scripts/test.sh` passes before handoff.
