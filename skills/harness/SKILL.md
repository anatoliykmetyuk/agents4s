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
  full runnable project (scripts/, companion messages, LLMActor, prompts,
  tests); add a new actor from a freshly added specs/*.md with minimal churn
  elsewhere; or outline specs for a multi-actor flow (e.g. routing,
  classification, drafting) then scaffold to Scala.
---

# Harness — actor specs to Pekko (Scala 3)

You turn **`specs/*.md`** into a **runnable** Scala 3 project: **Pekko Typed** for structure, **`agents4s.pekko.LLMActor`** + **`agents4s.cursor.CursorAgent`** for agentic steps. Mechanical work stays in actor `Behavior`s; LLM work runs in a **child** **`LLMActor`** that heartbeats on **`agent.isBusy`** so the parent never blocks on tmux polling.

Why this split: actors give you explicit message types, retries, and composition; **`LLMActor`** encapsulates the Cursor session, JSON result file, and uPickle parsing (see **`agents4s-pekko`**).

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
├── prompts/               # templates for agentic *task* steps
├── src/main/scala/<pkg>/
│   ├── Main.scala
│   ├── Orchestrator.scala    # example; one file per actor is typical
│   └── WorkerA.scala
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

For new projects, use [references/project-boilerplate.md](references/project-boilerplate.md): **`build.sbt`** (**`agents4s-pekko`** + testkit + ScalaTest), **`application.conf`** (default dispatcher), **`scripts/*.sh`**, **`.gitignore`**, `out/`.

For incremental adds: locate existing actors and **only** add new files + minimal edits to parents (spawn route, message forwarding).

## Step 3 — Message types in companion objects

For **each** actor:

- Put **all** incoming and outgoing message types in **`object ActorName`** (where `ActorName` is the name of the actor) as sealed traits + case classes (and traits for intents with multiple cases), as in [references/actor-guide.md](references/actor-guide.md).
- **Vertical layout:** group by **sealed family**. Put **no** blank lines between the sealed parent and its subtypes, or between siblings of the same sum type. Use **one** blank line **only** between unrelated groups (e.g. after the last `BlockReason` before `sealed trait Source`, after message ADTs and before a non-message helper such as `Ctx`). Do not double-space every case class— that obscures structure.
- Reference them everywhere as **`ActorName.MessageName`** (e.g. `WorkerA.InspectRequest`, `Orchestrator.WorkRequest`). **No** separate `protocol/` package.
- Requests that need replies include `replyTo: ActorRef[SomeResponse]` (or use adapters—see actor guide).

## Step 4 — Implement actors + LLM steps

- Each actor: `Behavior[ActorName.Command]` (or equivalent) from `ActorName.apply(...)` factory; file header `// Spec: specs/....md`; spec gets matching `<!-- impl: ... -->` when done.
- **Mechanical** procedure steps → ordinary Scala in the behavior.
- **Agentic** steps → spawn **`LLMActor.start[O](replyTo, agent, inputPrompt, outputInstructions)`** from **`agents4s.pekko`** (see [references/llm-actor-guide.md](references/llm-actor-guide.md)). Define **`O`** with uPickle **`ReadWriter`** and **`JsonSchema`**. The parent receives **`O | LLMActor.LLMError`** on **`replyTo`** (via **`messageAdapter`** or a dedicated ref). **Own** the **`Agent`** (e.g. **`CursorAgent`**): call **`agent.stop()`** when the child terminates — **`LLMActor`** does not stop the agent for you.
- **Do not** call **`agent.awaitIdle` / `sendPrompt` / `start`** directly from the parent’s **`receiveMessage`** thread; use **`LLMActor`** (or a custom child protocol) for that.

## Step 5 — `prompts/`

One template per agentic **task**; use `{{TOKENS}}`. **`LLMActor`** adds a second, library-generated prompt that instructs the model to write **JSON** matching schema **`O`** to a temp path — your markdown focuses on **what to do in the workspace**, not on hand-authoring the result-path contract.

## Step 6 — Wire `Main`

`Main`: load config, create `ActorSystem`, spawn top-level guardian / entry actor, parse CLI (`--workdir`, model, tmux socket, etc.). **Working directory** for Cursor should follow **supporting specs** and actor procedures—not necessarily the harness repo root unless a spec says so.

## Step 7 — Tests

Follow [references/testing.md](references/testing.md): `ActorTestKit`, `TestProbe`, stub **`Agent`** (e.g. copied **`StubAgent`**) — no real **`CursorAgent`** in unit tests.

## Checklist

- [ ] `specs/` present; **supporting specs** (`00-*` or similar) read for shared paths and context.
- [ ] **One actor per actor spec:** each actor spec has exactly one Scala actor (`// Spec:` + `<!-- impl: -->`); supporting specs do not get an actor.
- [ ] After any change: **spec markdown and Scala actor** (and prompts if needed) still match.
- [ ] `scripts/setup.sh`, `scripts/run.sh`, `scripts/test.sh` exist and `cd` to project root.
- [ ] `build.sbt` includes **`agents4s-pekko`** (+ testkit / ScalaTest).
- [ ] Messages only in companion objects; cross-refs use `ActorName.MessageName`; **blank lines only between sealed families** (see [references/actor-guide.md](references/actor-guide.md)).
- [ ] Agentic steps use **`LLMActor`** + `prompts/` task templates + typed **`O`**.
- [ ] Parent **`stop()`**s **`CursorAgent`** (or shared **`Agent`**) when the **`LLMActor`** child finishes.
- [ ] `out/` gitignored; `./scripts/test.sh` passes.

## Maintainer notes

- **Cursor / repo copy:** this folder ships with the [agents4s-core](../../agents4s-core) and [agents4s-pekko](../../agents4s-pekko) modules in this repo; harness **`build.sbt`** uses `publishLocal` artifacts.
- **Public API for harness work:** [references/llm-actor-guide.md](references/llm-actor-guide.md) (**`Agent`**, **`CursorAgent`**, **`LLMActor`**). When agents4s changes, update that guide so users rarely need the library sources.
