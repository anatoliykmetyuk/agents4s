---
name: actor-spec
description: >
  Create a markdown actor specification from the user prompt. Edit existing
  actor specifications to conform to the best practices outlined in this skill.
  Protocol messages are defined in specs/messages.md; each actor spec lists
  message names only under ## Receives. Use this skill whenever the user asks
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

These specifications are the primary input to the **actor-harness** skill, which generates **`messages.scala`** from **`specs/messages.md`** and builds each actor’s **`AcceptedMessages`** from that actor’s **`## Receives`** name list. Workflow steps that **send** a message must **name the recipient explicitly** (see **Outbound messages** below). The harness and human readers must never infer who receives an outbound message. A well-written spec produces a clean actor with minimal manual fixup.

## Suite layout: `messages.md` + actor specs

- **`specs/messages.md`** — **Single source of truth** for all inter-actor messages: pseudocode signatures, payloads, glosses, shared ADTs. The harness turns this into **`messages.scala`**.
- **Each actor spec** — **`## Suite references`** links **`messages.md`** (and **`definitions.md`** when used); **`## Receives`** lists **only message names** (backticks, one bullet per name). No payloads or descriptions under Receives — those live in **`messages.md`** only.

When you add or change a message, update **`messages.md`** and ensure every actor that can receive it includes that name in **`## Receives`**.

## Precision and conciseness (non-negotiable)

Each actor spec is **at most 100 lines**. Within that budget: **zero omissions, zero generalizations, zero ambiguity**. Brevity means cutting filler, **not** cutting required detail.

- Every workflow step that **sends a message** names the **recipient explicitly** (see **Outbound messages**).
- Every **subagent spawn** states what the child **does**, which **reply messages** the parent may get back, and what the parent **does on each reply** (including explicit **reply to \`replyTo\`** when the parent sends onward).
- Every **conditional** (success vs failure, branch A vs B) is written out; do not leave one path implicit.
- If a reader could ask “who receives this?” or “what happens next?”, the spec is incomplete.

## Messages are for inter-actor communication only

Put a message in **`messages.md`** only when a **different actor** (or the same actor via a **scheduled timer / delay**) sends or receives that envelope across a boundary.

- **Do not** invent messages for internal steps one actor performs in a single stretch of work (intermediate validation results, “emit to self” to sequence file moves, private state transitions). Those belong in the **Workflow** as imperative steps.
- **Allowed self-message:** schedule a message **to self** for delay, backoff, or periodic tick when the system model requires it (e.g. after a `scala.concurrent.duration.FiniteDuration`).
- **Rule of thumb:** before adding a message, ask: *which actor sends this* and *which actor receives it*? If only **this** actor is both sender and receiver (except timer wake-ups), it is **not** a protocol message — make it a workflow step.

## The actor specification format

```markdown
# <Actor Name> Actor Specification

## Actor Purpose

The purpose of the actor (high-level only; see “Actor Purpose” rules below).

## Suite references

- [`specs/messages.md`](specs/messages.md) — full message signatures and payloads for this harness.
- [`specs/definitions.md`](specs/definitions.md) — glossary for `_Term_` used in this spec (omit if unused).

## Receives

- `MessageName`
- `AnotherMessage`

## Workflow

A numbered, nested list of steps describing the workflow of the actor.
```

Each **`## Receives`** line is **only** `` `MessageName` `` — the name must match a message defined in **`specs/messages.md`**.

The specification should always start with the header `# <Actor Name> Actor Specification`. **Actor Purpose** is a **short, high-level** paragraph: what this actor is for in plain language — **no links, no “Definition source” lines, no file paths.**

**`## Suite references`** (after Purpose, before **`## Receives`**) is where **all** pointers to suite files belong: at minimum **`specs/messages.md`**, and **`specs/definitions.md`** (or your suite’s glossary path) when the harness uses a shared definitions file or any actor in the suite points at one. Do **not** scatter those links through Purpose or Workflow.

**`## Workflow`** may name message types and payloads in prose and use markdown links for **Spawn the Subagent [Name](path-to-actor-spec.md)** (and similar routing to **peer actor specs**). Do **not** put markdown links to **`messages.md`**, **`definitions.md`**, or other **catalog / glossary** files inside Workflow steps — readers use **`## Suite references`** for those.

Write in second person ("Your purpose is to...") — these specs read as instructions to the actor, and the second-person voice makes intent unambiguous both for human readers and for LLM agents that may implement the actor's agentic steps.

### Definitions (required coverage, not always a section in each actor file)

Use **underscore italics** (`_Term_`) **only** for **project-specific defined terms** — not for generic emphasis, and **not** for message payload field names (write those in backticks: `pullRequestNumber`, `destination`, `publicUrl`) so they are not confused with terms that belong in **Definitions**.

**Where definitions live**

Every `_Term_` in **Actor Purpose** or **Workflow** must be **defined somewhere readers can find**. (Message names under **`## Receives`** do not need definitions — full shapes are in **`messages.md`**.) Use **one** of these patterns:

1. **Suite definitions file (preferred when the harness has multiple actor specs)** — one shared file (e.g. `specs/definitions.md`) with every `_Term_` used in any actor in the suite. List it under **`## Suite references`** in **every** actor spec that belongs to that suite (same path for all). Keep that file **complete** for every term used in any citing spec.

2. **Inline** — `## Definitions` **immediately after** `## Suite references` and **before** `## Receives`, with one bullet per term: `- _Same Spelling_: short gloss`.

If there are **no** project-specific `_Term_` italics in Purpose or Workflow, you **omit** `## Definitions` and the definitions bullet under **`## Suite references`** (still keep **`specs/messages.md`** under Suite references when this actor is part of a protocol suite).

**Subagent specs** follow the same rule: shared suite file listed under **`## Suite references`**, or inline `## Definitions`, per file.

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

## Receives (actor spec)

Under **`## Receives`**, list **only** message **names** that this actor’s mailbox accepts — each as a single line:

```markdown
## Receives

- `PortPluginRequest`
- `GatekeeperOutcome`
```

Every name must appear in **`specs/messages.md`** with the full signature. Include messages from external callers, **child** actors, and peers.

**Do not** add a separate “Sends” section. Replies use message types defined in **`messages.md`**; the receiving actor lists that message name under **`## Receives`**.

**`## Suite references`** must appear **after** **`## Actor Purpose`** and **before** **`## Receives`** whenever this actor is part of a harness that uses **`specs/messages.md`**. Use it for links to the message catalog and shared glossary only — not for links to peer actor specs (those stay in **Workflow** spawn lines).

## Workflow

The Workflow is a numbered, nested list (up to two levels of nesting) describing the steps the actor follows when handling a message. The steps should be concrete and unambiguous.

Each step of the workflow should be chunked and scoped to the purpose of the actor. If a step that needs to be taken is too complex and involves multiple sub-steps, it should be delegated to a child actor. However if the step involves only a few substeps that aren't too complex, consider describing them as substeps of this step without delegating to a child actor. If a step involves calling an LLM Agent, it should also be delegated to a child actor. If in doubt, ask the user for clarification regarding the desired step chunk size.

### Workflow patterns

The following patterns appear frequently in well-written workflows. Read [examples/02-actor-get-it-passing.md](examples/02-actor-get-it-passing.md), [examples/01-messages.md](examples/01-messages.md), and [examples/06-definitions.md](examples/06-definitions.md) for a complete illustration.

**Defined terms.** Mark project-specific concepts with `_Term_` and ensure **every such term** is listed either in this file’s `## Definitions` or in the **suite definitions file** linked under **`## Suite references`** (see above).

**Concrete commands.** When a step involves a specific shell command, include it inline in backticks.

**Conditional branching.** When a step has different outcomes, spell out each branch explicitly.

**Outbound messages.** Every outbound message in the workflow uses **exactly one** of these two patterns:

1. **`reply to \`replyTo\` with \`Message(...)\`** — use the **`replyTo`** (or equivalent) **field name** from the **incoming request** in **`messages.md`** (if the catalog uses `sender`, `clientRef`, etc., use that exact name in the prose).
2. **`Spawn the Subagent [Name](path/to/actor-spec.md)`** — create a child actor; link to its spec with a markdown path. State what the child does, which reply messages the parent receives (names from **`messages.md`**), and nested substeps for each reply branch (see **Subagent delegation** below).

Do **not** use **“emit”**, **“send”**, or bare **“reply with \`Message(...)\`”** outside these two patterns.

Examples:

- Reply to \`replyTo\` with \`BatchItemFailed(jobId, image, lastError)\` and continue with remaining images.
- Spawn the Subagent [Resize Worker](resize-worker.md); spell out capability, reply message names, and nested branches per **Subagent delegation** below.

Conciseness is **not** an excuse to skip the pattern: *“Reply to \`replyTo\` with \`Blocked(reasons)\` and end”* is short and unambiguous.

**Child replies:** When a **child** answers with a message listed under your **`## Receives`** (e.g. \`GatekeeperOutcome\`), branch in the workflow on that type. Any **final outcome to the original client** still uses **reply to \`replyTo\` with \`...\`** when the request carried \`replyTo\`.

**Bounded loops.** When a step can be retried, state the bound: "go back to step 6... It is possible to return to step 6 no more than 3 times." Without an explicit bound, the actor-harness skill cannot know when to give up.

**Agentic steps.** Use **`(Agentic Step)`** only when **this** actor’s step is an **LLM call driven by a prompt** (judgment, natural-language reasoning, summarization, classification, or tool use mediated by the model). The harness routes those steps to agentic execution (e.g. `LLMActor`). **Do not** mark **Spawn the Subagent** as agentic: spawning a typed child and waiting on its **structured** reply is normal orchestration, not an LLM prompt on the parent. If the **child** is an LLM-backed actor, the **`(Agentic Step)`** markers belong in **that** child’s spec, on the steps where **it** runs prompts — not on the parent’s spawn line.

**Not agentic:** spawning children, `git` / shell commands, file moves, parsing structured data, branching on known message types. **Agentic:** e.g. “**(Agentic Step)** Call the LLM with prompt template `prompts/triage.txt` and the raw ticket text; map the model’s JSON output to \`TriageDecision(...)\`.”

**Subagent delegation.** A spawn step must make the **parent’s** protocol unambiguous:

1. **What the child does** — one clause (capability in plain language), **not** a recap of which message types appear in the child’s **`## Receives`**.
2. **Which reply messages** the parent may receive from that child (names matching **`messages.md`**).
3. **Nested substeps** — one per distinct reply: exact next action, including **reply to \`replyTo\`** whenever the parent sends a message onward.

**Bad:** *Spawn the Subagent [Resize Worker](resize-worker.md), which performs \`ResizeRequest\` / \`ResizeDone\` handling.* (Mailbox trivia; omits what the parent expects and does.)

**Good:** *Spawn the Subagent [Resize Worker](resize-worker.md). It resizes the image to configured bounds and replies with \`ResizeDone\` on success or \`WorkerFailed\` on failure.*
    1. On \`ResizeDone\`, proceed to the watermark stage.
    2. On \`WorkerFailed\`, increment the retry counter for this stage (at most 2 retries); if exhausted, reply to \`replyTo\` with \`BatchItemFailed(jobId, image, lastError)\`.

Template:

```markdown
1. Spawn the Subagent [Subagent Name](path/to/subagent/specification.md). It will <capability> and reply with `MessageA` / `MessageB` / … (names and shapes from the suite message catalog in Suite references).
    1. On `MessageA`, <next step; if sending onward, use reply to `replyTo` or another **Spawn the Subagent** step as needed>.
    2. On `MessageB`, <next step>.
```

Define the child’s full **`## Receives`**, **Workflow**, and any **Definitions** (inline or shared file) only in the **child** spec at the linked path. The **parent** must not copy the child’s internal steps, but **must** spell out how it reacts to **each** child reply.

## File Naming

Infer the naming convention from the existing files in the `specs/` folder or from the user's prompt. **`messages.md`** is the conventional name for the shared message catalog; keep it at **`specs/messages.md`** unless the project already uses another agreed name.

## Rules for Writing Specifications

- Keep each **actor** spec concise — no longer than 100 lines, and **every line must be precise** (no implied recipients, no hand-wavy “emit” or “send”).
- Each actor should do one specific job. Split complex jobs into multiple actors.
- Use precise language — avoid generalizations, omissions, ambiguity; **precision and the line budget are both mandatory**.
- Write in second person ("Your purpose is to...").
- If you use `_Term_` in Actor Purpose or Workflow, either add `## Definitions` (after `## Suite references`, before `## Receives`) with every term, **or** link the shared definitions file under **`## Suite references`** — no undefined italics. Do **not** link **`messages.md`** or **`definitions.md`** from inside **Workflow** steps.
- **`## Receives`:** one bullet per message name in backticks only (e.g. `- `PortPluginRequest``) — no payloads; full signatures live in **`messages.md`**.
- Keep **`messages.md`** complete and consistent: every name listed in any actor’s **`## Receives`** has a full definition line in **`messages.md`**.

## Evals

Benchmark prompts and assertion definitions for this skill live in [evals/evals.json](evals/evals.json). Use them with the skill-creator workflow when measuring regressions or comparing skill versions.
