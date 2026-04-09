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

Runs **unit tests only** (`TuiOpsTest`, `CursorAgentUnitTest`, `PathsTest`, `PaneTraitTest`).

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

Runs **`clean` → `coverage` → unit tests only** (`TuiOpsTest`, `CursorAgentUnitTest`, `PathsTest`, `PaneTraitTest`) → `coverageReport`. HTML output is under `target/scala-<version>/scoverage-report/`.

To aggregate coverage **including** integration tests (when `CURSOR_DRIVER_INTEGRATION` is set and `agent` / `tmux` are available; otherwise those tests are canceled):

```bash
scripts/coverage.sh -i
```

CI runs unit coverage on every push; an optional job also invokes `scripts/coverage.sh -i` (non-blocking) for a fuller report artifact when integration prerequisites exist.

## Harness skill

See `skills/harness/SKILL.md`. Install globally:

```bash
scripts/install-skill.sh
```
