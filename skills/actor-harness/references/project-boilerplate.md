# Project boilerplate (Scala 3 / sbt / Pekko Typed)

Use these templates when scaffolding a harness (**Step 1** of [actor-harness.md](../actor-harness.md)). Scripts live under **`scripts/`** and change directory to the **project root** (parent of `scripts/`) before running sbt.

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

See [library-api.md](library-api.md) for transitive dependency details. **agents4s-pekko** and **agents4s-testkit** are **local SNAPSHOT** artifacts (`0.1.0-SNAPSHOT`), resolved from `~/.ivy2/local` after `sbt publishLocal` in this repository (or `./scripts/install-skill.sh` there). Re-publish when the library changes.

```scala
val scala3Version = "3.8.3"

lazy val harness = project
  .in(file("."))
  .settings(
    name := "my-harness",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "me.anatoliikmt" %% "agents4s-pekko" % "0.1.0-SNAPSHOT",
      "me.anatoliikmt" %% "agents4s-testkit" % "0.1.0-SNAPSHOT" % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % "1.1.2" % Test,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
    ),
    // Tests that spin up ActorSystem benefit from forking
    Test / fork := true,
  )
```

## `src/main/resources/application.conf`

Default Pekko dispatcher only; **`LLMActor`** does not need a custom blocking dispatcher.

```hocon
pekko.actor.default-dispatcher {
  fork-join-executor {
    parallelism-min = 4
    parallelism-factor = 1.0
    parallelism-max = 64
  }
}
```

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

Reference layout for a generated harness (see [actor-harness.md](../actor-harness.md) for translation rules):

```
my-harness/
├── specs/
│   └── <spec-files>.md
├── scripts/
├── src/main/scala/<pkg>/
│   ├── messages.scala         # shared message protocol types
│   ├── Main.scala
│   └── actor1/                # per-actor package
│       ├── Actor1.scala
│       ├── Actor1Main.scala   # standalone main object to test this Actor all by itself
│       └── helpers1.scala
├── src/main/resources/
│   ├── application.conf
│   └── prompts/               # task templates — classpath (PromptTemplate loads prompts/<file>)
├── src/test/scala/<pkg>/
├── build.sbt
└── project/build.properties
```

## Prompt template example (task prompt only)

Load with **`PromptTemplate.load(...)`** and pass the string as **`inputPrompt`** to **`LLMActor.start`** (see [library-api.md](library-api.md) for classpath paths, **`outputInstructions`**, and integration).

```markdown
You are inspecting **one** work item. Follow the actor specification procedure.

## Context

| Field | Value |
|-------|-------|
| Item ID | {{ITEM_ID}} |
| Workspace | {{WORKSPACE}} |

## Tasks

1. Read the relevant files under `{{WORKSPACE}}`.
2. Decide: OK, NEEDS_WORK, or BLOCKED — with a short reason.

When finished, wait for the next instruction (structured JSON step).
```

Default **Cursor** model for live runs is **`composer-2-fast`** unless the user overrides it (matches repo integration defaults).
