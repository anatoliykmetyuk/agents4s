# Actor spec format (`specs/`)

Markdown files under `specs/` and the **Scala actors** they link to must **stay aligned**: change the spec ⇒ update the actor; change the actor ⇒ update the spec. Paths in specs and comments in Scala are relative to the **harness project root**.

## Actor specs and supporting specs

- **Actor specs** — each file describes **one** Pekko actor: purpose, message interface, and procedure. **One actor spec ⇒ one Scala actor** (one `Behavior`, one primary companion object for that actor’s messages).
- **Supporting specs** — shared context only: canonical paths, how outputs are stored, naming conventions, data files the workflow relies on. They **do not** define a single actor’s `Behavior`; use them while implementing **every** actor that touches those paths or rules. A common convention is **`00-*`** filenames (e.g. `00-shared-context.md`).

## Optional implementation pointer (after harnessing)

After code exists, an **actor** spec may include an HTML comment at the **very top** pointing at the Scala file (paths relative to project root):

```markdown
<!-- impl: src/main/scala/com/example/Orchestrator.scala -->

# Orchestrator
...
```

The matching Scala file starts with:

```scala
// Spec: specs/01-0-orchestrator.md
```

**Supporting specs** usually omit `<!-- impl: ... -->` (they are not tied to one actor).

Bidirectional links support **incremental** work: scan `specs/` for actor specs missing `<!-- impl: ... -->` to scaffold new actors without rewriting existing ones.

## Recommended sections (per actor spec)

### Title / name

A clear heading (e.g. `## The Worker`) matching the role in the procedure.

### Purpose

What this actor is responsible for in one short paragraph.

### Behavior

- **Receives**: bullet list of input messages with payload types (mirror Scala `case class` fields). Use intent names as `trait` with `case class` implementations when the spec describes several shapes of the same intent; otherwise a single `case class` is enough.
- **Responds with** (or **may reply with**): bullet list of outgoing messages. These become types in the actor’s **companion object** and are referenced elsewhere as `ActorName.MessageName`.

### Procedure

Numbered steps the actor follows when handling a message. Mark:

- **Mechanical** steps: filesystem, git, parsing, calls to pure Scala — implement in the `Behavior`.
- **Agentic** steps: judgment, code review, LLM-backed work — implement via `LlmBridge` + a file under `prompts/`, with a strict machine-readable output format (usually JSON) for the bridge to parse.

### Subactors

When the spec says “spawn / delegate to **Subactor X**”, the parent actor `context.spawn`s child behavior; child messages stay `ChildName.SomeMessage` across the project.

### Output artifacts (optional)

Files the actor or its LLM session should write (paths may be defined in a **supporting spec**).

### Reference material (optional)

Links or notes (APIs, migration guides) — not duplicated in generated code unless needed for compilation.

## Example fragment (Behavior)

```markdown
You may receive the following messages:

- `InspectRequest(path: Path, replyTo: ActorRef[InspectResponse])`

You may respond with:

- `AlreadyDone`
- `NeedsWork(details: String)`
```

In Scala this becomes `WorkerA.InspectRequest`, `WorkerA.AlreadyDone`, `WorkerA.NeedsWork`, all in `WorkerA`’s companion object.
