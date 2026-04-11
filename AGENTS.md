# Agent Guidelines

## Development Environment

Use the scripts in `scripts/` for setup, lint, and tests.

### Initial setup

```bash
scripts/setup.sh
```

Runs `sbt compile` and resolves dependencies (JDK 17+ required).

### Formatting

```bash
scripts/lint.sh
```

Runs `scalafmtCheckAll`.

### Running tests

```bash
scripts/test.sh
```

Runs **unit tests only** (`CursorTuiOpsTest`, `CursorAgentUnitTest`, `PathsTest`, `PaneTraitTest`, `LlmBridgeSpec`).

Integration tests (live `agent` + tmux) require:

```bash
scripts/test.sh -i
```

This sets `CURSOR_DRIVER_INTEGRATION=1`. Integration tests are skipped/canceled when the variable is not set to `1` / `true` / `yes`, or when `agent` / `tmux` are missing from `PATH`.

Extra arguments are forwarded to `sbt` (after the optional `-i` flag).

### Coverage (scoverage)

```bash
scripts/coverage.sh
```

Runs **`clean` → `coverage` → unit tests only** (`CursorTuiOpsTest`, `CursorAgentUnitTest`, `PathsTest`, `PaneTraitTest`, `LlmBridgeSpec`) → `coverageReport`. HTML output is under `agents4s/target/scala-<version>/scoverage-report/` and `agents4s-pekko/target/scala-<version>/scoverage-report/` (artifact uploads use a recursive glob).

To aggregate coverage **including** integration tests (when `CURSOR_DRIVER_INTEGRATION` is set and `agent` / `tmux` are available; otherwise those tests are canceled):

```bash
scripts/coverage.sh -i
```

CI runs unit coverage on every push; an optional job also invokes `scripts/coverage.sh -i` (non-blocking) for a fuller report artifact when integration prerequisites exist.

## Harness skill

The harness skill turns a **`specs/`** directory (markdown actor specifications) into a **Scala 3 Apache Pekko Typed** project you can run with sbt.

- **Hybrid workflow:** rote steps are **Scala** in actor behaviors; judgment-heavy steps use **`agents4s.cursor.CursorAgent`** behind a non-blocking **LlmBridge** (blocking work on a dedicated dispatcher, not on the default actor dispatcher).
- **Message layout:** each actor’s input and output types live in its **companion object** and are referenced project-wide as **`ActorName.MessageName`** (no separate `protocol/` package).
- **Links:** specs and code cross-reference with `<!-- impl: … -->` in markdown and `// Spec: specs/…` in Scala so you can add new `specs/*.md` and implement **incrementally** without rewriting the whole system.
- **Project shape:** generated harnesses use **`scripts/setup.sh`**, **`scripts/run.sh`**, **`scripts/test.sh`** (repo root), **`prompts/`** for agentic templates, and **`skills/harness/references/`** (`spec-format.md`, `project-boilerplate.md`, `actor-guide.md`, `llm-bridge-guide.md`, `testing.md`).

Read **`skills/harness/SKILL.md`** for the full workflow. Install the skill globally:

```bash
scripts/install-skill.sh
```
