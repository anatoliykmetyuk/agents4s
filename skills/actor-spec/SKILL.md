---
name: actor-spec
description: >
  Create a markdown actor specification from the user prompt. Edit existing
  actor specifications to conform to the best practices outlined in this skill.
  Protocol messages are defined in specs/messages.md; each actor spec lists
  message names only under ### Receives. Use this skill whenever the user asks
  to write, create, draft, or edit an actor spec, agent spec, or actor
  specification; when they want to define a new actor, add an actor to specs/,
  describe an actor's messages and workflow, or convert a rough description of
  agent behavior into a structured spec file. Also use when the user asks to
  review or improve an existing spec for clarity, completeness, or consistency
  with the rest of the specs/ folder.
---

# Actor Specification

You turn the user prompt into a markdown actor specification — the specification of a Pekko Typed actor. Write the specification to the file specified by the user, or to the `specs/` folder in the workspace by default.

## Why these specs matter

These specifications are the primary input to the **actor-harness** skill, which generates **`messages.scala`** from **`specs/messages.md`** and builds each actor’s **`AcceptedMessages`** from that actor’s **`### Receives`** name list. Workflow steps can say **reply with \`MessageName(...)\`** without naming the recipient actor: the recipient must list that message name under **`### Receives`**, and the full type comes from **`messages.md`**. A well-written spec produces a clean actor with minimal manual fixup.

## Suite layout: `messages.md` + actor specs

- **`specs/messages.md`** — **Single source of truth** for all inter-actor messages: pseudocode signatures, payloads, glosses, shared ADTs. The harness turns this into **`messages.scala`**.
- **Each actor spec** — **`## Messaging Protocol` → `### Receives`** lists **only message names** (backticks, one bullet per name). No payloads or descriptions here — those live in **`messages.md`** only.

When you add or change a message, update **`messages.md`** and ensure every actor that can receive it includes that name in **`### Receives`**.

## The actor specification format

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

- `MessageName`
- `AnotherMessage`

## Workflow

A numbered, nested list of steps describing the workflow of the actor.
```

Each **`### Receives`** line is **only** `` `MessageName` `` — the name must match a message defined in **`specs/messages.md`**.

The specification should always start with the header `# <Actor Name> Actor Specification`. The purpose should be a single paragraph describing the actor's purpose. Write in second person ("Your purpose is to...") — these specs read as instructions to the actor, and the second-person voice makes intent unambiguous both for human readers and for LLM agents that may implement the actor's agentic steps.

### Definitions (required coverage, not always a long section in this file)

Use **underscore italics** (`_Term_`) **only** for **project-specific defined terms** — not for generic emphasis, and **not** for message payload field names (write those in backticks: `pullRequestNumber`, `destination`, `publicUrl`) so they are not confused with terms that belong in **Definitions**.

**Where definitions live**

Every `_Term_` in **Actor Purpose**, **Messaging Protocol** (message names in **`### Receives`** do not need definitions — full shapes are in **`messages.md`**), or **Workflow** must be **defined somewhere readers can find**. Use **one** of these patterns:

1. **Inline (default)** — `## Definitions` **immediately after** `## Actor Purpose` and **before** `## Messaging Protocol**, with one bullet per term: `- _Same Spelling_: short gloss`.

2. **Suite definitions file** — when **either** the user tells you a path for definitions (create or update that file as needed) **or** you **discover** an existing definitions file in the `specs/` tree (e.g. `specs/definitions.md`, `specs/DEFINITIONS.md`, `specs/glossary.md`, or a name the project already uses). Then you **may omit** the full `## Definitions` bullet list **from this actor file**. Instead, **end `## Actor Purpose`** with a short, explicit pointer — for example:  
   `**Definition source:** all `_Term_` below are defined in [`specs/definitions.md`](specs/definitions.md).`  
   Keep that shared file **complete**: every italic term used in **any** actor spec that points at it must appear there with a gloss.

If there are **no** project-specific `_Term_` italics in those sections, you **omit** `## Definitions` and any definition-source line.

**Subagent specs** follow the same rule: inline `## Definitions`, or the same suite file + pointer in Purpose, per file.

If the user later moves to inline definitions only, restore a full `## Definitions` section in each affected spec and trim redundant pointers.

## `specs/messages.md` — message definitions

Create or update **`specs/messages.md`** alongside actor specs. It holds **every** message the system uses, each defined **exactly once**.

Suggested header:

```markdown
# Messages

All inter-actor messages for this harness. Pseudocode only; the actor-harness skill maps these to Scala types in `messages.scala`.
```

### Payload types: Scala first, flat, stdlib-only

**Type priority**

1. Prefer **Scala standard library** types first (`String`, `Int`, `Boolean`, `Long`, `List[...]`, `Option[...]`, `Either[...]`, `Map[...]`, `Set[...]`, `scala.concurrent.duration.FiniteDuration`, and so on).
2. Use **Java standard library** types second, always **fully qualified** (e.g. `java.nio.file.Path`, `java.net.URL`, `java.time.Instant`).
3. Do **not** reference types from external libraries unless the user explicitly allows them.

**Flat messages — avoid one-off wrappers**

Keep payloads flat: use stdlib types directly in the message.

**When a custom ADT is OK**

Introduce a named algebraic type (sealed-style variants in prose) only when the **same** shape is **reused across more than one message**.

**Why:** The actor-harness skill turns these into Scala 3 traits and case classes.

### List layout for each message in `messages.md`

**Each message is exactly one list line** — no line break between the signature and its gloss.

**Required shape** (one line, ASCII hyphen between signature and gloss):

```markdown
- `MessageName(...)` - What this message does.
```

That is: a leading `- `, the pseudocode signature in backticks, then **space, hyphen, space**, then the gloss on the **same** line.

Do **not** put the gloss on the following line or indent it under the signature. Do **not** use an em dash between signature and gloss — use **ASCII hyphen** ` - `.

## Messaging Protocol (actor spec)

Under **`### Receives`**, list **only** message **names** that this actor’s mailbox accepts — each as a single line:

```markdown
### Receives

- `PortPluginRequest`
- `GatekeeperOutcome`
```

Every name must appear in **`specs/messages.md`** with the full signature. Include messages from external callers, **child** actors, and peers.

**Do not** add `### Sends`. Replies use message types defined in **`messages.md`**; the receiving actor lists that message name under **`### Receives`**.

## Workflow

The Workflow is a numbered, nested list (up to two levels of nesting) describing the steps the actor follows when handling a message. The steps should be concrete and unambiguous.

Each step of the workflow should be chunked and scoped to the purpose of the actor. If a step that needs to be taken is too complex and involves multiple sub-steps, it should be delegated to a child actor. However if the step involves only a few substeps that aren't too complex, consider describing them as substeps of this step without delegating to a child actor. If a step involves calling an LLM Agent, it should also be delegated to a child actor. If in doubt, ask the user for clarification regarding the desired step chunk size.

### Workflow patterns

The following patterns appear frequently in well-written workflows. Read [examples/01-0-actor-get-it-passing.md](examples/01-0-actor-get-it-passing.md) and [examples/messages.md](examples/messages.md) for a complete illustration.

**Defined terms.** Mark project-specific concepts with `_Term_` and ensure **every such term** is listed either in this file’s `## Definitions` or in the **suite definitions file** you cite at the end of Actor Purpose (see above).

**Concrete commands.** When a step involves a specific shell command, include it inline in backticks.

**Conditional branching.** When a step has different outcomes, spell out each branch explicitly.

**Replies.** To send a reply, name the message and payload as in **`messages.md`**, e.g. **reply with \`Blocked(reasons)\`** or **tell \`replyTo\` with \`PortingComplete(reports)\`**. You do **not** need to link the recipient actor — the recipient’s **`### Receives`** must include that message name; the harness resolves the full type from **`messages.md`**.

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

Infer the naming convention from the existing files in the `specs/` folder or from the user's prompt. **`messages.md`** is the conventional name for the shared message catalog; keep it at **`specs/messages.md`** unless the project already uses another agreed name.

## Rules for Writing Specifications

- Keep each **actor** spec concise — no longer than 100 lines.
- Each actor should do one specific job. Split complex jobs into multiple actors.
- Use precise language — avoid generalizations, omissions, ambiguity.
- Write in second person ("Your purpose is to...").
- If you use `_Term_` in Actor Purpose or Workflow, either add `## Definitions` (after Purpose, before Messaging) with every term, **or** point to a shared definitions file under `specs/` at the end of Actor Purpose — no undefined italics.
- **`### Receives`:** one bullet per message name in backticks only (e.g. `- `PortPluginRequest``) — no payloads; definitions live in **`messages.md`**.
- Keep **`messages.md`** complete and consistent: every name listed in any actor’s **`### Receives`** has a full definition line in **`messages.md`**.

## Evals

Benchmark prompts and assertion definitions for this skill live in [evals/evals.json](evals/evals.json). Use them with the skill-creator workflow when measuring regressions or comparing skill versions.
