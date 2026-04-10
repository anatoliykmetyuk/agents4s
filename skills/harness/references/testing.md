# Harness testing (Pekko Typed + ScalaTest)

Actor-system harnesses should stay **fast and deterministic** in CI: no real **`CursorAgent`**, no tmux, no live `agent` binary in unit tests.

## Principles

1. **Unit tests:** `ActorTestKit`, `BehaviorTestKit`, `TestProbe`; **mock** the LLM port.
2. **Integration tests (optional):** separate suite, gated by env var if you ever drive a real agent—same pattern as agents4s integration tests.
3. **Real filesystem** under `Files.createTempDirectory` / `os.Path` when testing file workflows.
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

## Shared fixtures

- One `ActorTestKit` per test class (`afterAll`: `testKit.shutdownTestKit()`).
- Reuse a helper `def tmpWorkspace: os.Path` for workspace-root tests.
- Keep **prompt templates** under `prompts/` in fixture dirs only if assertions need template text.

## Checklist

- [ ] ScalaTest in **Test** scope; `Test / fork := true` if using `ActorSystem`.
- [ ] No real `CursorAgent` / tmux in default `test`.
- [ ] Cover bounded-retry and early-exit branches from `specs/`.
- [ ] `./scripts/test.sh` passes before handoff.
