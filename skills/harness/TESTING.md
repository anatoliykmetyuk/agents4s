# Harness Testing Strategy (ScalaTest)

Every harness has the same layered architecture; tests validate orchestration **without** real agents or external commands.

## Principles

1. **No real agents.** Mock `CursorAgent` or abstract behind a small trait your tests substitute.
2. **No real subprocess** for git/cli calls — inject stubs or wrap `os.proc` in a test seam.
3. **Real filesystem under a temp dir** — `java.nio.file.Files.createTempDirectory` / `os.Path`.
4. **One test file per layer** when the SOP is large enough to justify it.

## Test layers

| Layer | Test file(s) | Mock | Assert |
|-------|----------------|------|--------|
| Pure helpers | `*HelpersTest.scala` | — | Return values |
| Data file I/O | `*DataTest.scala` | — | Parse/write on temp files |
| Mechanical | `*MechanicalTest.scala` | process stub | Args / exit codes |
| Agent wrappers | `*AgentsTest.scala` | fake `CursorAgent` | Prompt text, workspace, model |
| Pipeline | `*PipelineTest.scala` | all of | Branch coverage |

## Project layout

```
<harness>/
  src/test/scala/.../
    HelpersTest.scala
    DataTest.scala
    MechanicalTest.scala
    AgentsTest.scala
    PipelineTest.scala
  test.sh
```

Add `scalatest` as a **Test** dependency in `build.sbt`.

## Test runner (`test.sh`)

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec sbt test "$@"
```

## Shared fixtures

Use **traits** or **abstract classes** with `beforeEach` / helper methods instead of pytest `conftest.py`:

- Build temp `os.Path` roots per test.
- Stub prompt templates under `prompts/`.
- Factory for a fake `CursorAgent` that records `start` / `sendPrompt` / `awaitDone` calls.

**Classpath** — keep main sources under `src/main/scala` and test under `src/test/scala`; sbt wires the classpath.

## Common testing patterns

| Need | ScalaTest approach |
|------|-------------------|
| Replace a dependency at test time | Constructor / `given` dependency injection |
| Stub out a collaborator | Test doubles, stub classes |
| Temporary filesystem root | `Files.createTempDirectory` + `os.Path` |
| Parameterized test cases | `TableDrivenPropertyChecks` or loops over examples |
| Assert an exception is thrown | `assertThrows` / `intercept` |

## Checklist

- [ ] ScalaTest on the **Test** scope in `build.sbt`.
- [ ] No real `CursorAgent` in unit tests.
- [ ] Pipeline tests cover every early-exit branch.
- [ ] `./test.sh` runs green before handing off.
