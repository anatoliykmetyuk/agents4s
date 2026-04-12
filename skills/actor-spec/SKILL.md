---
name: actor-spec
description: >
  Create a markdown actor specification from the user prompt. Edit existing
  actor specifications to conform to the best practices outlined in this skill.
  Use this skill whenever the user asks to write, create, draft, or edit an
  actor spec, agent spec, or actor specification; when they want to define a
  new actor, add an actor to specs/, describe an actor's messages and workflow,
  or convert a rough description of agent behavior into a structured spec file.
  Also use when the user asks to review or improve an existing spec for clarity,
  completeness, or consistency with the rest of the specs/ folder.
---

# Actor Specification

You turn the user prompt into a markdown actor specification — the specification of a Pekko Typed actor. Write the specification to the file specified by the user, or to the `specs/` folder in the workspace by default.

## Why these specs matter

These specifications are the primary input to the **actor-harness** skill, which translates each actor spec into a runnable Scala 3 Pekko Typed actor. The harness maps messages to Scala types (including union-typed mailboxes), workflow steps to behavior logic, and subagent-spawning steps to child actor lifecycles. A well-written spec produces a clean actor with minimal manual fixup; a vague or inconsistent spec forces rework. Keep this downstream translation in mind — be concrete enough that someone (or a tool) reading the spec can produce the actor without guessing.

## The Specification Format

The specification follows this structure:

```markdown
# <Actor Name> Actor Specification

## Actor Purpose

The purpose of the actor.

## Definitions

- _Term A_: what it names (path, branch, threshold, …).
- _Term B_: …

(If the suite uses a **shared definitions file** instead, see “Definitions” rules below — you may cite that file and omit the bullet list here.)

## Messaging Protocol

### Receives

- `MessageName(payload: Type)` - Short description of what this message means and when it arrives.

### Sends

- `ResponseName(payload: Type)` - Short description of what this reply communicates.

## Workflow

A numbered, nested list of steps describing the workflow of the actor.
```

The specification should always start with the header `# <Actor Name> Actor Specification`. The purpose should be a single paragraph describing the actor's purpose. Write in second person ("Your purpose is to...") — these specs read as instructions to the actor, and the second-person voice makes intent unambiguous both for human readers and for LLM agents that may implement the actor's agentic steps.

### Definitions (required coverage, not always a long section in this file)

Use **underscore italics** (`_Term_`) **only** for **project-specific defined terms** — not for generic emphasis, and **not** for message payload field names (write those in backticks: `pullRequestNumber`, `destination`, `publicUrl`) so they are not confused with terms that belong in **Definitions**.

**Where definitions live**

Every `_Term_` in **Actor Purpose**, **Messaging Protocol** (including message descriptions), or **Workflow** must be **defined somewhere readers can find**. Use **one** of these patterns:

1. **Inline (default)** — `## Definitions` **immediately after** `## Actor Purpose` and **before** `## Messaging Protocol**, with one bullet per term: `- _Same Spelling_: short gloss`.

2. **Suite definitions file** — when **either** the user tells you a path for definitions (create or update that file as needed) **or** you **discover** an existing definitions file in the `specs/` tree (e.g. `specs/definitions.md`, `specs/DEFINITIONS.md`, `specs/glossary.md`, or a name the project already uses). Then you **may omit** the full `## Definitions` bullet list **from this actor file**. Instead, **end `## Actor Purpose`** with a short, explicit pointer so nothing is ambiguous — for example:  
   `**Definition source:** all `_Term_` below are defined in [`specs/definitions.md`](specs/definitions.md).`  
   Keep that shared file **complete**: every italic term used in **any** actor spec that points at it must appear there with a gloss. When you add new terms to an actor, update the shared file in the same edit.

If there are **no** project-specific `_Term_` italics in those sections, you **omit** `## Definitions` and any definition-source line.

**Subagent specs** follow the same rule: inline `## Definitions`, or the same suite file + pointer in Purpose, per file.

If the user later moves to inline definitions only, restore a full `## Definitions` section in each affected spec and trim redundant pointers.

## Messaging Protocol

The messaging protocol has exactly two subsections, in this order: `### Receives` (messages from non-child actors) and `### Sends` (messages back to those actors). Only include types exchanged with external actors — omit messages used only with spawned children.

The messages are pseudocode in the form `MessageName(payload1: PayloadType1, ..., payloadN: PayloadTypeN)`. `MessageName` should describe intent in at most five words. Do not write full Scala implementations (no method bodies, no imports block).

### Payload types: Scala first, flat, stdlib-only

**Type priority**

1. Prefer **Scala standard library** types first (`String`, `Int`, `Boolean`, `Long`, `List[...]`, `Option[...]`, `Either[...]`, `Map[...]`, `Set[...]`, `scala.concurrent.duration.FiniteDuration`, and so on).
2. Use **Java standard library** types second, always **fully qualified** (e.g. `java.nio.file.Path`, `java.net.URL`, `java.time.Instant`).
3. Do **not** reference types from external libraries unless the user explicitly allows them.

**Flat messages — avoid one-off wrappers**

Keep payloads flat: use stdlib types directly in the message. For example, prefer `PortPluginRequest(url: java.net.URL)` over `PortPluginRequest(source: GitHubSource)` when `GitHubSource` is only a single-field wrapper around `java.net.URL`.

**When a custom ADT is OK**

Introduce a named algebraic type (sealed-style variants in prose) only when the **same** shape is **reused across more than one message** in the protocol. Example: a `BlockerReason` shared by `Blocked(reasons: List[BlockerReason])` and `ValidationFailed(reason: BlockerReason)` documents domain semantics once and stays consistent. Do not invent wrappers that appear in a single message only.

**Why:** The actor-harness skill turns these into Scala 3 traits and case classes. Noise types and third-party classes create boilerplate and dependency risk; flat stdlib-first payloads keep generated code small and predictable.

### List layout for each message

Under `### Receives` and `### Sends`, use a **markdown list**. **Each message is exactly one list line** — no line break between the signature and its gloss.

**Required shape** (one line, ASCII hyphen between signature and gloss):

```markdown
- `MessageName(...)` - What this message does.
```

That is: a leading `- `, the pseudocode signature in backticks, then **space, hyphen, space**, then the gloss on the **same** line.

Do **not** put the gloss on the following line or indent it under the signature; that reads as a broken list and is hard to scan. Do **not** use an em dash between signature and gloss here — use **ASCII hyphen** ` - ` so the pattern is uniform.

Keep each one-line gloss to at most one short sentence or two, or tighten with a semicolon if needed.

## Workflow

The Workflow is a numbered, nested list (up to two levels of nesting) describing the steps the actor follows when handling a message. The steps should be concrete and unambiguous.

Each step of the workflow should be chunked and scoped to the purpose of the actor. If a step that needs to be taken is too complex and involves multiple sub-steps, it should be delegated to a child actor. However if the step involves only a few substeps that aren't too complex, consider describing them as substeps of this step without delegating to a child actor. If a step involves calling an LLM Agent, it should also be delegated to a child actor. If in doubt, ask the user for clarification regarding the desired step chunk size.

### Workflow patterns

The following patterns appear frequently in well-written workflows. Read [examples/01-0-actor-get-it-passing.md](examples/01-0-actor-get-it-passing.md) for a complete illustration showing all of them in context.

**Defined terms.** Mark project-specific concepts with `_Term_` and ensure **every such term** is listed either in this file’s `## Definitions` or in the **suite definitions file** you cite at the end of Actor Purpose (see above). Do not italicize a term in Purpose, Messaging Protocol, or Workflow unless it is covered by one of those two places.

**Concrete commands.** When a step involves a specific shell command, include it inline in backticks — e.g., "Check it out via `git checkout <_Default Branch_>`." This removes ambiguity about what "update the branch" means in practice.

**Conditional branching.** When a step has different outcomes, spell out each branch explicitly: "If it has cleared the porting, proceed with the next steps. If it has denied porting, stop the workflow here and report to the requesting agent with a corresponding message according to your messaging protocol."

**Bounded loops.** When a step can be retried, state the bound: "go back to step 6... It is possible to return to step 6 no more than 3 times." Without an explicit bound, the actor-harness skill cannot know when to give up.

**Agentic steps.** When a step is meant to be carried out by an **LLM agent** (judgment, natural-language reasoning, or tool use that is not pure deterministic code), start the step text with **`(Agentic Step)`** so the actor-harness skill can route it to agentic execution (e.g. `LLMActor`). Subagent delegation often implies an agentic child, but the marker applies to **this** actor’s step: use it when **this** step’s work is agentic (including “spawn subagent and interpret its reply” when that interpretation is non-mechanical). Purely deterministic steps (fixed shell commands, file moves, simple conditionals on structured data) do not need the marker.

Example: `4. **(Agentic Step)** Spawn the Subagent [Reviewer](specs/reviewer.md) and, based on its structured reply, decide whether to post comments.`

**Subagent delegation.** A workflow step may spawn a child actor to delegate work to it. An actor-spawning step follows the following pattern:

```markdown
1. Spawn the Subagent [Subagent Name](path/to/subagent/specification.md), which does X.
    1. Subagent response 1 handling
    2. Subagent response 2 handling
    3. Subagent response N handling
```

Subagent Name is the name of the subagent and the path is the path to the subagent's specification file relative to the specification folder. Defining a subagent step means you also need to define the specification of that subagent at the path specified. Do not specify the subagent's behavior, workflow or messaging protocol in the parent actor's specification. You may have one line describing at a high-level what the subagent does, however, all the specification should be contained in the subagent's specification file.

## File Naming

Infer the naming convention from the existing files in the `specs/` folder or from the user's prompt. If neither provides a clear convention, ask the user before creating the file.

## Rules for Writing Specifications

- Keep it concise — no longer than 100 lines.
- Each actor should do one specific job. Split complex jobs into multiple actors.
- Use precise language — avoid generalizations, omissions, ambiguity.
- Write in second person ("Your purpose is to...").
- If you use `_Term_` in Actor Purpose, Messaging Protocol, or Workflow, either add `## Definitions` (after Purpose, before Messaging) with every term, **or** point to a shared definitions file under `specs/` (user-provided or existing) at the end of Actor Purpose and maintain that file — no undefined italics.
- In **Messaging Protocol**, each Receives/Sends message is **one** list line: pseudocode signature in backticks, then a space, ASCII hyphen, space, then the gloss — all on the same line; never wrap the gloss onto the next line.

## Evals

Benchmark prompts and assertion definitions for this skill live in [evals/evals.json](evals/evals.json). Use them with the skill-creator workflow when measuring regressions or comparing skill versions.
