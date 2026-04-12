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

Runs the **full test suite** (`sbt test`). Integration tests (`CursorAgentIntegrationTest`, `LLMActorIntegrationSpec`) are included and will execute when `agent` and `tmux` are on `PATH`; they are automatically skipped otherwise.

To run **unit tests only** (faster cycle without integration):

```bash
scripts/test.sh -u
```

Integration tests are **opt-out**: set `CURSOR_DRIVER_INTEGRATION=0` (or `false` / `no` / `off`) to skip them even when `agent` and `tmux` are available. CI sets this variable to `0` on the required build job. The model used by live integration defaults to `composer-2-fast` and can be overridden via `CURSOR_DRIVER_MODEL`.

Extra arguments are forwarded to `sbt` (after the optional flag).

### Coverage (scoverage)

```bash
scripts/coverage.sh
```

Runs **`clean` → `coverage` → full test suite** → `coverageReport`. Integration tests run when `agent` and `tmux` are on `PATH` (skipped otherwise). HTML output is under `agents4s-core/target/scala-<version>/scoverage-report/`, `agents4s-testkit/target/scala-<version>/scoverage-report/`, and `agents4s-pekko/target/scala-<version>/scoverage-report/` (artifact uploads use a recursive glob).

To collect coverage for **unit tests only**:

```bash
scripts/coverage.sh -u
```

CI runs coverage with `CURSOR_DRIVER_INTEGRATION=0` on the required build job (integration skipped); an optional job runs `scripts/coverage.sh` without the opt-out for a fuller report artifact when integration prerequisites exist.

## Harness skill

The harness skill turns a **`specs/`** directory (markdown actor specifications) into a **Scala 3 Apache Pekko Typed** project you can run with sbt.

- **Hybrid workflow:** rote steps are **Scala** in actor behaviors; judgment-heavy steps use **`agents4s.cursor.CursorAgent`** via **`agents4s.pekko.LLMActor`** (heartbeat-driven child actor, not the parent dispatcher).
- **Message layout:** each actor’s input and output types live in its **companion object** and are referenced project-wide as **`ActorName.MessageName`** (no separate `protocol/` package).
- **Links:** specs and code cross-reference with `<!-- impl: … -->` in markdown and `// Spec: specs/…` in Scala so you can add new `specs/*.md` and implement **incrementally** without rewriting the whole system.
- **Project shape:** generated harnesses use **`scripts/setup.sh`**, **`scripts/run.sh`**, **`scripts/test.sh`** (repo root), **`prompts/`** for agentic templates, and **`skills/harness/references/`** (`spec-format.md`, `project-boilerplate.md`, `actor-guide.md`, `llm-actor-guide.md`, `testing.md`).

Read **`skills/harness/SKILL.md`** for the full workflow. Install the skill globally:

```bash
scripts/install-skill.sh
```
