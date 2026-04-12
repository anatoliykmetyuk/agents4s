
# Actor harness — actor-spec markdown to Pekko (Scala 3)

You turn `specs/*.md` files that follow the `actor-spec` skill format into a runnable Scala 3 project that uses Pekko Typed to express the specified actor behavior. The Scala 3 project structure is defined by the [Project Boilerplate](references/project-boilerplate.md). This transformation of the actor specs into the Pekko Actors is defined by the [Actor Translation Guide](references/actor-translation-guide.md). In the process, you will be using the `agents4s` library to implement agentic steps, consult the [library API](references/library-api) to understand how to use it.

## Step 1 — Discover actor specs, message definitions, and term definitions

1. Scan `specs/` (recursively if the project uses subfolders) for markdown files.

 whose first `#` heading matches `# <Actor Name> Actor Specification` (any actor name). Those files are actor specifications; each maps to exactly one Scala `object` (name derived from `<Actor Name>` — PascalCase, no spaces).
2. Scan `specs/` to locate where message definitions live (pseudocode signatures for the inter-actor protocol) and, when present, where term definitions live (glossary for `_Term_` and domain vocabulary). Filenames are not fixed — infer from content and structure (and anything the user tells you). The message-definitions file is the single source of truth for protocol shapes; generate or update `messages.scala` from it (one canonical Scala type per message). The term-definitions content informs Actor Purpose / Workflow interpretation and naming in generated code; it does not emit Scala protocol types by itself.
3. Other markdown (e.g. definitions-only files with no actor heading) is supporting context, not an actor — no `Behavior` for those files alone.
4. For each actor spec, parse `## Receives`: each bullet is a message name only (e.g. `` `PortPluginRequest` ``). Build `AcceptedMessages` for that actor as the union of the corresponding types from `messages.scala` (plus internal-only types).
5. Parse each actor spec’s Workflow for subagent lines: `Spawn the Subagent [Name](relative/path.md)`. Build a graph: parent → child spec paths. Ensure every linked child has its own actor spec file at that path (or scaffold stubs the user will fill).
6. Reply steps in the Workflow name messages (e.g. “reply with `Blocked(reasons)`”). Resolve types from `messages.scala`; the receiving actor must list that message name under `## Receives`.
7. Add a `// Spec: specs/....md` line at the top of each generated actor `.scala` file for traceability.

## Step 2 — Scaffold or extend the project

For new projects, use [references/project-boilerplate.md](references/project-boilerplate.md): `build.sbt`, `application.conf`, `scripts/*.sh`, `.gitignore`, `out/`, the suite’s message definitions under `specs/`, and `messages.scala`.

For incremental adds: extend the message-definitions markdown and `messages.scala` with new message types; add `## Receives` names to affected actors; add new actor files + minimal edits to parents (spawn, routing, `AcceptedMessages` unions).

## Step 3 — One Scala object per actor specification + shared messages

1. `messages.scala` (package level): define every message type from the suite’s message definitions markdown (see [references/actor-translation-guide.md](references/actor-translation-guide.md)). One canonical definition per message; `replyTo` types follow those definitions.
2. For each actor, create a Scala package and an object inside according to the [Actor Translation Guide](references/actor-translation-guide.md).

## Step 4 — Declarative behaviors and helpers

- Keep each `receiveMessage` / behavior block minimal: prefer one clear action per workflow bullet; name branches after the spec’s conditions.
- If logic is non-trivial (heavy parsing, domain validation, file orchestration), move it to top-level defs in a helper file under a dedicated subpackage for that actor:
  - `src/main/scala/<pkg>/<actorpackage>/helpers.scala` (or split by concern if large).
- The actor object stays in `.../<actorpackage>/<ActorName>.scala`. Use one helper file by default; split only when helpers address different concerns or domains.

## Step 5 — Agentic steps and LLM

For Workflow lines that start with `(Agentic Step)` (or that spawn an LLM-driven child per actor-spec conventions):

- Spawn `LLMActor.start[O](replyTo, agent, inputPrompt, outputInstructions)` with `agents4s.cursor.CursorAgent`. Default model: `composer-2-fast` unless the user specifies another.
- `replyTo` is typically a `context.messageAdapter` mapping `O | LLMActor.LLMError` into your `AcceptedMessages`.
- Define `O` with uPickle `ReadWriter` and `JsonSchema` (see [references/library-api.md](references/library-api.md)).
- Own the `Agent`: call `agent.stop()` when the LLM child terminates — `LLMActor` does not stop the agent for you (use `context.watchWith` or equivalent).
- Do not call `agent.awaitIdle` / `sendPrompt` / `start` from the parent’s `receiveMessage` thread; use `LLMActor` for the LLM session.
- Put task text in `src/main/resources/prompts/` (e.g. `inspect.md`, `gatekeeper.md`) so sbt packages them as classpath resources. Load with `agents4s.prompt.PromptTemplate`:
  - `PromptTemplate.load(name, values)` — loads the resource `prompts/<name>` (via the context class loader), then replaces `{{KEY}}` placeholders using `substitute` and the `values` map. Example: `PromptTemplate.load("inspect.md", Map("ITEM_ID" -> id, "WORKSPACE" -> ws))`.
  - `PromptTemplate.loadResource(name)` — template body only (no substitution); same `prompts/<name>` classpath path.
  - Do not keep editable templates only under a repo-root `prompts/` folder unless you copy them into `src/main/resources/prompts/` for the packaged app/tests.
- Structured field meanings go in `outputInstructions` (not only in the markdown file). Details and API table: [references/library-api.md](references/library-api.md) (`PromptTemplate` section).

## Step 6 — Wire `Main`

Load config, create `ActorSystem`, spawn the top-level actor from the user’s entry spec, parse CLI (`--workdir`, model override, tmux socket, etc.). Workspace for `CursorAgent` should follow term definitions (wherever they live under `specs/`, or inline in actor specs) and Actor Purpose in the specs—not necessarily the harness repo root unless the spec says so.

## Step 7 — Tests

Follow [references/testing.md](references/testing.md): `StubAgent`, `LLMActor.start`, `TestProbe[... | ...]` for union message types.

## Library API (concise)

Implementing agents should rely on [references/library-api.md](references/library-api.md) — `Agent`, `CursorAgent`, `LLMActor`, `PromptTemplate`, `StubAgent`, and output type constraints — without opening the agents4s sources.

## Checklist

- [ ] Message definitions located under `specs/`; `messages.scala` generated/updated from them.
- [ ] `specs/` scanned; only `# ... Actor Specification` files become actors; supporting glossary/definitions files read for context.
- [ ] `messages.scala` holds all protocol types; one object per actor spec with `AcceptedMessages` from name lists (+ internals / LLM).
- [ ] `(Agentic Step)` lines use `LLMActor` + `CursorAgent`, default model `composer-2-fast` unless overridden; task templates under `src/main/resources/prompts/` loaded with `PromptTemplate.load` / `loadResource`.
- [ ] Parent `stop()`s `CursorAgent` when the run is done; helpers live in actor subpackage when needed.
- [ ] `scripts/setup.sh`, `scripts/run.sh`, `scripts/test.sh` exist; `build.sbt` includes `agents4s-pekko` and `agents4s-testkit` `% Test`.
- [ ] `out/` gitignored; `./scripts/test.sh` passes.

## Evals

Benchmark prompts and assertion definitions for this skill live in [evals/evals.json](evals/evals.json). Use them with the skill-creator workflow when measuring regressions or comparing skill versions.

## Maintainer notes

- This folder ships beside [agents4s-core](../../agents4s-core), [agents4s-testkit](../../agents4s-testkit), and [agents4s-pekko](../../agents4s-pekko); harness `build.sbt` uses `publishLocal` artifacts.
- Specs format is owned by [skills/actor-spec/SKILL.md](../actor-spec/SKILL.md) — keep actor-harness aligned when actor-spec section names change.
