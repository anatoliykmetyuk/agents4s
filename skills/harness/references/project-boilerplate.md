# Project boilerplate (Scala 3 / sbt / Pekko Typed)

Use these templates when scaffolding a harness (**Step 2** of `SKILL.md`). Scripts live under **`scripts/`** and change directory to the **project root** (parent of `scripts/`) before running sbt.

## `scripts/setup.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
sbt compile
```

## `scripts/run.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec sbt "runMain com.example.harness.Main" "$@"
```

Replace `com.example.harness.Main` with your entrypoint.

## `scripts/test.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec sbt test "$@"
```

## `build.sbt` (snippet)

**agents4s** is a **local SNAPSHOT** (`0.1.0-SNAPSHOT`), resolved from `~/.ivy2/local` after `sbt publishLocal` in the agents4s repository (or `./scripts/install-skill.sh` there). Re-publish when the library changes.

**Pekko** — Apache Pekko Typed for Scala 3 (adjust version if needed):

```scala
val scala3Version = "3.8.3"

lazy val harness = project
  .in(file("."))
  .settings(
    name := "my-harness",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.2",
      "me.anatoliikmt" %% "agents4s" % "0.1.0-SNAPSHOT",
      "com.lihaoyi" %% "os-lib" % "0.11.8",
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % "1.1.2" % Test,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
    ),
    // Tests that spin up ActorSystem benefit from forking
    Test / fork := true,
  )
```

Add a JSON library (e.g. **ujson** / Circe) if the `LlmBridge` parses model output in-process.

## `src/main/resources/application.conf`

```hocon
pekko.actor.default-dispatcher {
  fork-join-executor {
    parallelism-min = 4
    parallelism-factor = 1.0
    parallelism-max = 64
  }
}

# Use for CursorAgent / blocking IO inside spawned futures
blocking-llm-dispatcher {
  type = Dispatcher
  executor = "thread-pool-executor"
  thread-pool-executor {
    fixed-pool-size = 16
  }
  throughput = 1
}
```

In Scala reference the dispatcher with `ExecutionContext` from `context.system.dispatchers.lookup("blocking-llm-dispatcher")` (see Pekko docs for typed `DispatcherSelector`).

## `project/build.properties`

```
sbt.version=1.12.9
```

## `.gitignore`

```
target/
.bsp/
.metals/
.bloop/
out/
```

## Directory layout (reference)

```
my-harness/
├── specs/
├── scripts/
├── prompts/
├── src/main/scala/<pkg>/
│   ├── Main.scala
│   ├── ...                    # one Scala file per actor typical
│   └── LlmBridge.scala
├── src/main/resources/application.conf
├── src/test/scala/<pkg>/
├── build.sbt
└── project/build.properties
```

## Prompt template example (agentic step + machine output)

```markdown
You are inspecting **one** work item. Follow the spec in the attached procedure.

## Context

| Field | Value |
|-------|-------|
| Item ID | {{ITEM_ID}} |
| Workspace | {{WORKSPACE}} |

## Tasks

1. Read the relevant files under `{{WORKSPACE}}`.
2. Decide: OK, NEEDS_WORK, or BLOCKED — with a short reason.

## Output (required)

Write **only** valid JSON to `{{OUTPUT_JSON_PATH}}` with this shape:

```json
{
  "status": "OK | NEEDS_WORK | BLOCKED",
  "reason": "string or null"
}
```
```
