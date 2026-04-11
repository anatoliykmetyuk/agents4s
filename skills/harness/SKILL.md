---
name: harness
description: >
  Turn a folder of markdown actor specifications (specs/) into a Scala 3 Pekko
  Typed actor system where mechanical steps run as code and agentic steps
  delegate to LLM agents via agents4s CursorAgent. Use this skill whenever the
  user wants to harness an actor organization, scaffold actors from markdown
  specs, convert agent specs into a Pekko actor system, implement an agentic
  workflow as actors, or mentions "harness", "actor system", "specs folder",
  or "actor spec". Also use when the user has markdown files describing
  agents/actors with message interfaces and wants runnable Scala, or when they
  want to add a new actor to an existing actor-based harness project--even if
  they do not say "Pekko" or "actor" by name but describe spawning workers,
  validators, orchestrators, or subagents. Typical asks: harness specs/ into a
  full runnable project (scripts/, companion messages, LlmBridge, prompts,
  tests); add a new actor from a freshly added specs/*.md with minimal churn
  elsewhere; or outline specs for a multi-actor flow (e.g. routing,
  classification, drafting) then scaffold to Scala.
---

# Harness — actor specs to Pekko (Scala 3)

You turn **`specs/*.md`** into a **runnable** Scala 3 project: **Pekko Typed** for structure, **`agents4s.cursor.CursorAgent`** for agentic steps. Mechanical work stays in actor `Behavior`s; LLM work goes through a **non-blocking bridge** so actor threads never block on tmux.

Why this split: actors give you explicit message types, retries, and composition; the bridge isolates blocking CLI/tmux work and file-based handoff from the LLM.

## When this applies

- A **`specs/`** directory (or the user wants you to create one) describing actors, messages, and procedures.
- Mix of **deterministic** steps (git, file IO, parsing) and **LLM** steps (judgment, code changes).
- **Iterative** evolution: new `specs/*.md` files should add actors without rewriting the whole tree—use bidirectional `<!-- impl: ... -->` / `// Spec: ...` links (see Step 1).

## Layout (project root)

```
my-harness/
├── specs/                 # markdown; paired with Scala (keep in sync both ways)
├── scripts/
│   ├── setup.sh
│   ├── run.sh
│   └── test.sh
├── prompts/               # templates for agentic steps
├── src/main/scala/<pkg>/
│   ├── Main.scala
│   ├── Orchestrator.scala    # example; one file per actor is typical
│   ├── WorkerA.scala
│   └── LlmBridge.scala       # CursorAgent adapter
├── src/main/resources/application.conf
├── build.sbt
└── out/                     # gitignored artifacts
```

Shell scripts live under **`scripts/`** and `cd` to the repo root before invoking sbt (see boilerplate).

## Step 1 — Read `specs/`

1. Identify **actor specs** (each one maps to **exactly one** Scala actor) and **supporting specs** (shared context: paths, data layout, conventions—**no** `Behavior` / one-actor mapping). Often **`00-*`** files are supporting; the rest are actor specs (see [references/spec-format.md](references/spec-format.md)).
2. When implementing an actor, combine its spec with whatever **supporting specs** define (same filesystem, trackers, output locations).
3. Build a mental graph: which actor **spawns** or **messages** which child (subactors).
4. **Greenfield vs incremental:** if a spec already has `<!-- impl: path/to/Actor.scala -->`, treat it as wired—only add missing impl links for **new** specs.
5. **Specs and actors stay in sync both ways.** If the user changes an actor spec (markdown)—messages, procedure, retries—you **update** the corresponding Scala actor (and prompts if agentic steps changed). If the user changes the actor (Scala), you **update** the linked `specs/*.md` body so it describes the same behavior. Keep `// Spec:` / `<!-- impl: -->` links accurate; trivial automation-only notes in prose are optional.

## Step 2 — Scaffold or extend the project

For new projects, use [references/project-boilerplate.md](references/project-boilerplate.md): **`build.sbt`** (Pekko Typed + agents4s + ScalaTest), **`application.conf`** (blocking dispatcher), **`scripts/*.sh`**, **`.gitignore`**, `out/`.

For incremental adds: locate existing actors and **only** add new files + minimal edits to parents (spawn route, message forwarding).

## Step 3 — Message types in companion objects

For **each** actor:

- Put **all** incoming and outgoing message types in **`object ActorName`** (where `ActorName` is the name of the actor) as sealed traits + case classes (and traits for intents with multiple cases), as in [references/actor-guide.md](references/actor-guide.md).
- **Vertical layout:** group by **sealed family**. Put **no** blank lines between the sealed parent and its subtypes, or between siblings of the same sum type. Use **one** blank line **only** between unrelated groups (e.g. after the last `BlockReason` before `sealed trait Source`, after message ADTs and before a non-message helper such as `Ctx`). Do not double-space every case class— that obscures structure.
- Reference them everywhere as **`ActorName.MessageName`** (e.g. `WorkerA.InspectRequest`, `Orchestrator.WorkRequest`). **No** separate `protocol/` package.
- Requests that need replies include `replyTo: ActorRef[SomeResponse]` (or use adapters—see actor guide).

## Step 4 — Implement actors + bridge

- Each actor: `Behavior[ActorName.Command]` (or equivalent) from `ActorName.apply(...)` factory; file header `// Spec: specs/....md`; spec gets matching `<!-- impl: ... -->` when done.
- **Mechanical** procedure steps → ordinary Scala in the behavior.
- **Agentic** steps → `LlmBridge` (or injected port): read [references/llm-bridge-guide.md](references/llm-bridge-guide.md) for **`Agent` / `CursorAgent`**, multi-turn sessions, **`LlmPort`**, and a full **`pipeToSelf`** example. **Do not** search the filesystem for agents4s sources—use that guide only. Use **`Future`** on the blocking dispatcher + **`pipeToSelf`**, with a strict file/JSON contract for model output.
- **`LlmBridge` messages** also live on `LlmBridge`’s companion object and are referenced as `LlmBridge.Run`, etc.

## Step 5 — `prompts/`

One template per agentic sub-step; use `{{TOKENS}}`. Always tell the model **where** and **in what format** to write results (JSON schema or path) so the bridge can parse and turn files into **`ActorName.SomeMessage`**.

## Step 6 — Wire `Main`

`Main`: load config, create `ActorSystem`, spawn top-level guardian / entry actor, parse CLI (`--workdir`, model, tmux socket, etc.). **Working directory** for Cursor should follow **supporting specs** and actor procedures—not necessarily the harness repo root unless a spec says so.

## Step 7 — Tests

Follow [references/testing.md](references/testing.md): `ActorTestKit`, `TestProbe`, **mock LlmPort**—no real `CursorAgent` in unit tests.

## Checklist

- [ ] `specs/` present; **supporting specs** (`00-*` or similar) read for shared paths and context.
- [ ] **One actor per actor spec:** each actor spec has exactly one Scala actor (`// Spec:` + `<!-- impl: -->`); supporting specs do not get an actor.
- [ ] After any change: **spec markdown ⇄ Scala actor** (and prompts if needed) still match.
- [ ] `scripts/setup.sh`, `scripts/run.sh`, `scripts/test.sh` exist and `cd` to project root.
- [ ] `build.sbt` includes Pekko Typed + agents4s (+ JSON lib if used).
- [ ] `src/main/resources/application.conf` defines blocking dispatcher for bridge.
- [ ] Messages only in companion objects; cross-refs use `ActorName.MessageName`; **blank lines only between sealed families** (see [references/actor-guide.md](references/actor-guide.md)).
- [ ] Agentic steps have `prompts/` templates with machine-readable output instructions.
- [ ] No blocking `CursorAgent` calls on the default actor dispatcher.
- [ ] `out/` gitignored; `./scripts/test.sh` passes.

## Maintainer notes

- **Cursor / repo copy:** this folder ships with the [agents4s-core](../../agents4s-core) module in this repo; `build.sbt` uses `publishLocal` artifacts.
- **CursorAgent API:** treat [references/llm-bridge-guide.md](references/llm-bridge-guide.md) as the **canonical public API** summary for harness work (`Agent`, `CursorAgent`, bridge examples). When agents4s changes, update that guide so users never need to hunt the library sources.
