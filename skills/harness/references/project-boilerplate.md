# Project Boilerplate (Scala / sbt)

Use these templates when scaffolding a harness (Step 2 of `SKILL.md`).

## `setup.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sbt compile
```

## `run.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec sbt "runMain com.example.harness.Main" "$@"
```

Replace `com.example.harness.Main` with your entrypoint.

## `test.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec sbt test "$@"
```

## `build.sbt` (snippet)

Publish or path-include **cursor-driver-scala**, then depend on it:

```scala
val scala3Version = "3.8.3"

lazy val harness = project
  .in(file("."))
  .settings(
    name := "my-harness",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.8",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
      // "io.github.USER" %% "cursor-driver" % "0.1.0", // or unmanaged / publishLocal
    ),
  )
```

For a **path dependency** while developing locally (adjust the path):

```scala
// Compile dependency on sibling checkout:
// unmanagedBase := file("../cursor-driver-scala/target/scala-3.8.3") // not typical; prefer publishLocal:
// In cursor-driver-scala: `sbt publishLocal`
// Then: "cursor-driver" %% "cursor-driver" % "0.1.0"
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

## Prompt template example

Prompt templates are language-agnostic — they work identically regardless of the harness language.

```markdown
You are running the discovery workflow for **one** plugin.

## Context

| Field | Value |
|-------|-------|
| Plugin ID | {{PLUGIN_ID}} |
| Clone path | {{CLONE_DIR}} |
| Tracker file | {{TRACKER_PATH}} |

## Tasks

1. Read `{{CLONE_DIR}}/build.sbt`.
2. Decide porting status using the markers below.
3. Edit `{{TRACKER_PATH}}`: update Status for ID {{PLUGIN_ID}}.

## Rules

- Only change Status to "Already Ported" if markers confirm it.
```
