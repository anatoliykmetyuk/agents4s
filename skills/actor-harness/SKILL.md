---
name: actor-harness
description: >
  Turn actor specifications in specs/ (markdown files produced by the actor-spec
  skill: `# <Actor Name> Actor Specification`, Receives as name-only lists,
  full definitions in specs/messages.md) into a Scala 3 Apache Pekko Typed project:
  messages.scala generated from messages.md, one object per actor, union-typed
  AcceptedMessages, declarative behavior defs, and LLM steps via agents4s LLMActor
  + CursorAgent. Use this
  skill whenever the user wants to harness specs into runnable Scala, scaffold
  Pekko actors from actor-spec markdown, implement an agentic workflow as actors,
  or mentions "actor-harness", "harness specs", "specs to Scala", "LLMActor
  from specs", or wants to add a new actor from a freshly added specs/*.md
  that follows the actor-spec format. Also use when they describe workers,
  validators, orchestrators, or subagents spawned from specs—even if they do not
  say "Pekko" by name. Pair with the actor-spec skill for writing/editing specs.
---

# Actor harness — actor-spec markdown to Pekko (Scala 3)

You turn **`specs/*.md`** files that follow the **actor-spec** format into a **runnable** Scala 3 project: **Pekko Typed** for structure, **`agents4s.pekko.LLMActor`** + **`agents4s.cursor.CursorAgent`** for **(Agentic Step)** workflow lines. Mechanical work stays in actor `Behavior`s; LLM work runs in a **child** **`LLMActor`** so the parent never blocks on tmux polling while the agent runs.

**Input contract:** specs are written with the **actor-spec** skill. Canonical title: `# <Actor Name> Actor Specification`. Sections: **Actor Purpose**, optional **Definitions**, **Messaging Protocol** (**`### Receives`** — **message names only**, backticks, one per line), **Workflow**. **Full** message signatures live in **`specs/messages.md`**; the harness generates **`messages.scala`** from that file.

**Why this split:** actors give explicit message types, retries, and composition; **`LLMActor`** encapsulates the Cursor session, JSON result file, and uPickle parsing (see [references/library-api.md](references/library-api.md)). Shared protocol types live in **`messages.scala`**, sourced from **`specs/messages.md`** (see [references/actor-translation-guide.md](references/actor-translation-guide.md)).

## When this applies

- A **`specs/`** directory containing **actor specifications** (title pattern above) and **`specs/messages.md`** (or add it when harnessing).
- Mix of **deterministic** steps (git, file IO, parsing) and **LLM** steps (marked **`(Agentic Step)`** in the Workflow).
- **Iterative** evolution: new `specs/*.md` files add actors; wire parents to spawn children per Workflow links.

## Layout (project root)

```
my-harness/
├── specs/
│   └── messages.md        # canonical message definitions (payloads, ADTs)
├── scripts/
│   ├── setup.sh
│   ├── run.sh
│   └── test.sh
├── src/main/scala/<pkg>/
│   ├── messages.scala     # generated from specs/messages.md
│   ├── Main.scala
│   ├── GetItPassing.scala    # one object per actor (example names)
│   └── getitpassing/         # optional: package for one actor + helpers
│       ├── GetItPassing.scala
│       └── helpers.scala
├── src/main/resources/
│   ├── application.conf
│   └── prompts/             # task templates for agentic steps ({{TOKENS}}); classpath resources
├── build.sbt
└── out/                     # gitignored artifacts
```

Shell scripts live under **`scripts/`** and `cd` to the repo root before invoking sbt (see [references/project-boilerplate.md](references/project-boilerplate.md)).

## Step 1 — Discover actor specs and messages

1. Read **`specs/messages.md`** — this is the **single source of truth** for protocol message shapes. Generate or update **`messages.scala`** from it (one canonical Scala type per message in **`messages.md`**).
2. Scan **`specs/`** (recursively if the project uses subfolders) for markdown files whose **first** `#` heading matches **`# <Actor Name> Actor Specification`** (any actor name). Those files are **actor specifications**; each maps to **exactly one** Scala `object` (name derived from `<Actor Name>` — PascalCase, no spaces).
3. Other markdown (e.g. shared **Definitions** files, glossaries) is **supporting context**, not an actor — no `Behavior` for those files alone.
4. For **each** actor spec, parse **`### Receives`**: each bullet is a **message name** only (e.g. `` `PortPluginRequest` ``). Build **`AcceptedMessages`** for that actor as the union of the corresponding types from **`messages.scala`** (plus internal-only types).
5. Parse each actor spec’s **Workflow** for **subagent** lines: `Spawn the Subagent [Name](relative/path.md)`. Build a graph: parent → child spec paths. Ensure every linked child has its own actor spec file at that path (or scaffold stubs the user will fill).
6. **Reply steps** in the Workflow name messages (e.g. **reply with \`Blocked(reasons)\`**). Resolve types from **`messages.scala`**; the receiving actor must list that message name under **`### Receives`** — no **`reply to [Actor](path)`** parsing required.
7. Add a **`// Spec: specs/....md`** line at the top of each generated actor `.scala` file for traceability (optional but recommended).

## Step 2 — Scaffold or extend the project

For new projects, use [references/project-boilerplate.md](references/project-boilerplate.md): **`build.sbt`**, **`application.conf`**, **`scripts/*.sh`**, **`.gitignore`**, `out/`, **`specs/messages.md`**, **`messages.scala`**.

For incremental adds: extend **`specs/messages.md`** and **`messages.scala`** with new message types; add **`### Receives`** names to affected actors; add new actor files + minimal edits to parents (spawn, routing, **`AcceptedMessages`** unions).

## Step 3 — One Scala object per actor specification + shared messages

1. **`messages.scala`** (package level): define **every** message type defined in **`specs/messages.md`** (see [references/actor-translation-guide.md](references/actor-translation-guide.md)). One canonical definition per message; **`replyTo`** types follow **`messages.md`**.

2. For **each** actor spec:

- **`object <ActorName>`** in **one primary `.scala` file** (see naming in Step 1).
- **`type AcceptedMessages`:** union of all types **named** in **this** actor’s **`### Receives`** (resolved via **`messages.scala`**), **plus** private/internal types (timers, **`LLMActor`** / **`messageAdapter`** wiring) not named in the spec.
- **`Deps`**, **`def apply`**, behavior **`def`**s implementing the **Workflow**; **reply** steps use message types from **`messages.scala`** (e.g. **`req.replyTo ! Outcome(...)`**).

Mechanical translation rules: [references/actor-translation-guide.md](references/actor-translation-guide.md).

## Step 4 — Declarative behaviors and helpers

- Keep each **`receiveMessage`** / behavior block **minimal**: prefer **one clear action** per workflow bullet; name branches after the spec’s conditions.
- If logic is **non-trivial** (heavy parsing, domain validation, file orchestration), move it to **top-level defs** in a **helper file** under a **dedicated subpackage** for that actor:
  - `src/main/scala/<pkg>/<actorpackage>/helpers.scala` (or split by concern if large).
- The **actor object** stays in **`.../<actorpackage>/<ActorName>.scala`**. Use **one** helper file by default; split only when helpers address **different concerns or domains**.

## Step 5 — Agentic steps and LLM

For Workflow lines that start with **`(Agentic Step)`** (or that spawn an LLM-driven child per actor-spec conventions):

- Spawn **`LLMActor.start[O](replyTo, agent, inputPrompt, outputInstructions)`** with **`agents4s.cursor.CursorAgent`**. **Default model:** **`composer-2-fast`** unless the user specifies another.
- **`replyTo`** is typically a **`context.messageAdapter`** mapping **`O | LLMActor.LLMError`** into your **`AcceptedMessages`**.
- Define **`O`** with uPickle **`ReadWriter`** and **`JsonSchema`** (see [references/library-api.md](references/library-api.md)).
- **Own the `Agent`:** call **`agent.stop()`** when the LLM child terminates — **`LLMActor`** does **not** stop the agent for you (use **`context.watchWith`** or equivalent).
- **Do not** call **`agent.awaitIdle` / `sendPrompt` / `start`** from the parent’s **`receiveMessage`** thread; use **`LLMActor`** for the LLM session.
- Put **task** text in **`src/main/resources/prompts/`** (e.g. `inspect.md`, `gatekeeper.md`) so sbt packages them as **classpath** resources. Load with **`agents4s.prompt.PromptTemplate`**:
  - **`PromptTemplate.load(name, values)`** — loads the resource **`prompts/<name>`** (via the context class loader), then replaces **`{{KEY}}`** placeholders using **`substitute`** and the `values` map. Example: **`PromptTemplate.load("inspect.md", Map("ITEM_ID" -> id, "WORKSPACE" -> ws))`**.
  - **`PromptTemplate.loadResource(name)`** — template body only (no substitution); same **`prompts/<name>`** classpath path.
  - Do **not** keep editable templates only under a repo-root `prompts/` folder unless you copy them into `src/main/resources/prompts/` for the packaged app/tests.
- Structured field meanings go in **`outputInstructions`** (not only in the markdown file). Details and API table: [references/library-api.md](references/library-api.md) (`PromptTemplate` section).

## Step 6 — Wire `Main`

Load config, create **`ActorSystem`**, spawn the top-level actor from the user’s entry spec, parse CLI (`--workdir`, model override, tmux socket, etc.). **Workspace** for **`CursorAgent`** should follow **Definitions** / **Actor Purpose** in the specs—not necessarily the harness repo root unless the spec says so.

## Step 7 — Tests

Follow [references/testing.md](references/testing.md): **`StubAgent`**, **`LLMActor.start`**, **`TestProbe[... | ...]`** for union message types.

## Library API (concise)

Implementing agents should rely on **[references/library-api.md](references/library-api.md)** — **`Agent`**, **`CursorAgent`**, **`LLMActor`**, **`PromptTemplate`**, **`StubAgent`**, and output type constraints — without opening the agents4s sources.

## Checklist

- [ ] **`specs/messages.md`** read; **`messages.scala`** generated/updated from it.
- [ ] `specs/` scanned; only **`# ... Actor Specification`** files become actors; supporting defs/glossary files read for context.
- [ ] **`messages.scala`** holds all protocol types; **one object per actor spec** with **`AcceptedMessages`** from name lists (+ internals / LLM).
- [ ] **`(Agentic Step)`** lines use **`LLMActor`** + **`CursorAgent`**, default model **`composer-2-fast`** unless overridden; task templates under **`src/main/resources/prompts/`** loaded with **`PromptTemplate.load`** / **`loadResource`**.
- [ ] Parent **`stop()`**s **`CursorAgent`** when the run is done; helpers live in actor subpackage when needed.
- [ ] `scripts/setup.sh`, `scripts/run.sh`, `scripts/test.sh` exist; `build.sbt` includes **`agents4s-pekko`** and **`agents4s-testkit`** `% Test`.
- [ ] `out/` gitignored; `./scripts/test.sh` passes.

## Evals

Benchmark prompts and assertion definitions for this skill live in [evals/evals.json](evals/evals.json). Use them with the skill-creator workflow when measuring regressions or comparing skill versions.

## Maintainer notes

- This folder ships beside [agents4s-core](../../agents4s-core), [agents4s-testkit](../../agents4s-testkit), and [agents4s-pekko](../../agents4s-pekko); harness **`build.sbt`** uses `publishLocal` artifacts.
- **Specs format** is owned by **[skills/actor-spec/SKILL.md](../actor-spec/SKILL.md)** — keep actor-harness aligned when actor-spec section names change.
