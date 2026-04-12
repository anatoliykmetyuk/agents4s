---
name: actor-harness
description: >
  Turn actor specifications in specs/ (markdown files produced by the actor-spec
  skill: `# <Actor Name> Actor Specification`) into a Scala 3 Apache Pekko Typed
  project: one object per actor, union-typed accepted messages, declarative
  behavior defs, and LLM steps via agents4s LLMActor + CursorAgent. Use this
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

**Input contract:** specs are written with the **actor-spec** skill. Canonical title: `# <Actor Name> Actor Specification`. Sections: **Actor Purpose**, optional **Definitions**, **Messaging Protocol** (`### Receives`, `### Sends`), **Workflow**.

**Why this split:** actors give explicit message types, retries, and composition; **`LLMActor`** encapsulates the Cursor session, JSON result file, and uPickle parsing (see [references/library-api.md](references/library-api.md)).

## When this applies

- A **`specs/`** directory containing **actor specifications** (title pattern above).
- Mix of **deterministic** steps (git, file IO, parsing) and **LLM** steps (marked **`(Agentic Step)`** in the Workflow).
- **Iterative** evolution: new `specs/*.md` files add actors; wire parents to spawn children per Workflow links.

## Layout (project root)

```
my-harness/
├── specs/                 # actor-spec markdown (paired with Scala)
├── scripts/
│   ├── setup.sh
│   ├── run.sh
│   └── test.sh
├── src/main/scala/<pkg>/
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

## Step 1 — Discover actor specs

1. Scan **`specs/`** (recursively if the project uses subfolders) for markdown files whose **first** `#` heading matches **`# <Actor Name> Actor Specification`** (any actor name). Those files are **actor specifications**; each maps to **exactly one** Scala `object` (name derived from `<Actor Name>` — PascalCase, no spaces).
2. Other markdown (e.g. shared **Definitions** files, glossaries) is **supporting context**, not an actor — no `Behavior` for those files alone.
3. Parse each actor spec’s **Workflow** for **subagent** lines: `Spawn the Subagent [Name](relative/path.md)`. Build a graph: parent → child spec paths. Ensure every linked child has its own actor spec file at that path (or scaffold stubs the user will fill).
4. Add a **`// Spec: specs/....md`** line at the top of each generated actor `.scala` file for traceability (optional but recommended).

## Step 2 — Scaffold or extend the project

For new projects, use [references/project-boilerplate.md](references/project-boilerplate.md): **`build.sbt`**, **`application.conf`**, **`scripts/*.sh`**, **`.gitignore`**, `out/`.

For incremental adds: add new actor files + minimal edits to parents (spawn, routing, `AcceptedMessages` unions).

## Step 3 — One Scala object per actor specification

For **each** actor spec:

- **`object <ActorName>`** in **one primary `.scala` file** (see naming in Step 1).
- **Messages:** Implement the **Messaging Protocol** inside the object:
  - **`### Receives`** → incoming case classes (and sealed families when the spec describes variants). Requests that need replies: add **`replyTo: ActorRef[Response]`** using the Sends types for that interaction.
  - **`### Sends`** → reply/notification types the actor emits to **non-child** peers (per actor-spec rules).
  - **Child-only traffic:** the actor-spec omits messages used only with children. **Do not** define a child’s **`### Receives` / `### Sends`** in the parent file — they live under **`object TheChild`** in the **child’s** `.scala` file (see [references/actor-translation-guide.md](references/actor-translation-guide.md)). When the parent **`receiveMessage`** handles a child **`tell`**, **import** those message types from the child companion (e.g. **`import TheGatekeeper.GatekeeperResponse`**) or use **`TheGatekeeper.*`** in the union — **do not** reintroduce duplicate case classes in the parent to stand in for child messages.
- **`AcceptedMessages`:** A **Scala 3 union type** alias, e.g.  
  `type AcceptedMessages = PortPluginRequest | TheGatekeeper.GatekeeperResponse | TheWorker.WorkerDone | ...`  
  Include:
  - Every message from **`### Receives`** (for **this** actor only).
  - Every **child message type** this parent must accept from spawned children (**types declared on the child** — imported / FQCN).
  - **Internal-only** messages for **this** actor (timers, **`LLMActor`** / **`messageAdapter`** wiring) when needed — **not** substitutes for child protocol types.
- **Behaviors:** Implement **`def apply(deps): Behavior[AcceptedMessages]`** as the entry. Split the **Workflow** into **`def`**s returning **`Behavior[AcceptedMessages]`** — one def per phase/state so each block stays **short and mappable** to numbered workflow steps (e.g. `def awaitingGatekeeper(pluginId: String): Behavior[AcceptedMessages]`).

Mechanical translation rules, ADTs, and reply patterns: [references/actor-translation-guide.md](references/actor-translation-guide.md).

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

- [ ] `specs/` scanned; only **`# ... Actor Specification`** files become actors; supporting defs/glossary files read for context.
- [ ] **One object per actor spec**; **`AcceptedMessages`** union includes receives + internal/child completions (+ LLM completions).
- [ ] **`(Agentic Step)`** lines use **`LLMActor`** + **`CursorAgent`**, default model **`composer-2-fast`** unless overridden; task templates under **`src/main/resources/prompts/`** loaded with **`PromptTemplate.load`** / **`loadResource`**.
- [ ] Parent **`stop()`**s **`CursorAgent`** when the run is done; helpers live in actor subpackage when needed.
- [ ] `scripts/setup.sh`, `scripts/run.sh`, `scripts/test.sh` exist; `build.sbt` includes **`agents4s-pekko`** and **`agents4s-testkit`** `% Test`.
- [ ] `out/` gitignored; `./scripts/test.sh` passes.

## Evals

Benchmark prompts and assertion definitions for this skill live in [evals/evals.json](evals/evals.json). Use them with the skill-creator workflow when measuring regressions or comparing skill versions.

## Maintainer notes

- This folder ships beside [agents4s-core](../../agents4s-core), [agents4s-testkit](../../agents4s-testkit), and [agents4s-pekko](../../agents4s-pekko); harness **`build.sbt`** uses `publishLocal` artifacts.
- **Specs format** is owned by **[skills/actor-spec/SKILL.md](../actor-spec/SKILL.md)** — keep actor-harness aligned when actor-spec section names change.
