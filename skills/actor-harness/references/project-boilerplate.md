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

**agents4s-pekko** and **agents4s-testkit** are **local SNAPSHOT** artifacts (`0.1.0-SNAPSHOT`), resolved from `~/.ivy2/local` after `sbt publishLocal` in this repository (or `./scripts/install-skill.sh` there). Re-publish when the library changes.

Use **`me.anatoliikmt` %% `agents4s-pekko`** for **`LLMActor`** and uPickle JSON output types; it depends on **`agents4s-core`**, **Pekko Typed**, **uPickle**, and **upickle-jsonschema** transitively. Add **`agents4s-testkit`** ( **`StubAgent`** and future test helpers; depends on core only), **Pekko testkit**, and **ScalaTest** for tests.

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

No extra JSON library is required for **`LLMActor`** result parsing — uPickle is already on the classpath.

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

Actor-spec harnesses use **`specs/messages.md`** as the **canonical** definition of all inter-actor messages; **`messages.scala`** is generated from it. Each actor spec lists **message names only** under **`## Receives`**; **one Scala file per actor object**. If an actor needs **helper modules**, add a **subpackage** named after that actor (lower case) containing **`ActorName.scala`** + **`helpers.scala`** (see **Step 4** in `SKILL.md`).

```
my-harness/
├── specs/
│   └── messages.md        # full message signatures & ADTs (source for messages.scala)
├── scripts/
├── src/main/scala/<pkg>/
│   ├── messages.scala         # shared protocol types (from specs/messages.md)
│   ├── Main.scala
│   ├── GetItPassing.scala
│   └── getitpassing/          # optional per-actor package
│       ├── GetItPassing.scala
│       └── helpers.scala
├── src/main/resources/
│   ├── application.conf
│   └── prompts/               # task templates — classpath (PromptTemplate loads prompts/<file>)
├── src/test/scala/<pkg>/
├── build.sbt
└── project/build.properties
```

## Prompt template example (task prompt only)

Store templates under **`src/main/resources/prompts/`** so they are on the **classpath** at **`prompts/<filename>`** (required for **`PromptTemplate.load`** / **`loadResource`**).

The **task** prompt describes what the model should do in the workspace. **`LLMActor`** adds a separate **result** prompt with JSON path and schema; describe output **fields** in code via **`outputInstructions`**, not necessarily in this file.

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

Load with **`PromptTemplate.load("inspect.md", Map("ITEM_ID" -> ..., "WORKSPACE" -> ...))`** and pass the string as **`inputPrompt`** to **`LLMActor.start`**. Use **`outputInstructions`** such as: *`status` is one of OK, NEEDS_WORK, BLOCKED; `reason` is optional text.*

Default **Cursor** model for live runs is **`composer-2-fast`** unless the user overrides it (matches repo integration defaults).
