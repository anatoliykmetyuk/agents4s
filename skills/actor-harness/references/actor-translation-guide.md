# Actor spec → Scala translation

Use this when implementing **[skills/actor-harness/SKILL.md](../SKILL.md)** against specs produced by **[skills/actor-spec/SKILL.md](../../actor-spec/SKILL.md)**.

## Title → object name

- Spec heading: `# Get It Passing Actor Specification`
- Scala: `object GetItPassing` (PascalCase, strip “Actor Specification” from the title words — use the actor name phrase only).

Add `// Spec: specs/01-0-actor-get-it-passing.md` (or actual path) at the top of the file.

## Messaging Protocol → types

### `### Receives`

Each bullet ``- `MessageName(...)` - gloss`` becomes a **`final case class MessageName(...)`** (or a **`case object`** if no parameters).

- If the gloss implies a **request/response** to an external caller, add **`replyTo: ActorRef[...]`** where **`...`** is a union or sealed type built from **`### Sends`** for that interaction.
- Payload types in the spec are **Scala/Java stdlib** — mirror them (`java.nio.file.Path`, `Option[...]`, etc.).

### `### Sends`

Each outgoing message becomes a **response or notification** type in the same object:

- Parameterless responses → **`case object`** or **`case class`** with fields as in the signature.
- **Sum types** in the spec (e.g. `LibraryBlocker | PluginBlocker | OtherBlocker`) → **`sealed trait`** + **`case class`** variants **tightly packed** (no blank lines inside one sealed family; one blank line between unrelated groups — see layout below).

### Messages not in the spec (implementation)

- **Private/internal** messages for this actor only: timers, `messageAdapter` results, **parent-only completion envelopes** (see below). Example: `private case object WorkerTimedOut`.
- **LLM completion:** after `messageAdapter`, use a single wrapper or discriminate `O | LLMActor.LLMError` — include these cases in **`AcceptedMessages`**.

### Where each actor’s protocol lives

- **`### Receives`** and **`### Sends`** for actor **A** are **`final case class` / `sealed trait`** definitions in **`object A`** in **`A.scala`** (that actor’s file). **Never** define another actor’s public messages in a parent or sibling file.
- **Child actors** are the same: **`object TheGatekeeper`**, **`AcceptedMessages`**, and all of that spec’s Receives/Sends types live in **`TheGatekeeper.scala`** (or its package). The **parent** does **not** host the child’s protocol.
- When the parent must handle a child outcome, add **private** envelopes **in the parent** whose **fields reference** types from the child companion (e.g. `TheGatekeeper.SomeResponse`), or use **`messageAdapter`** to map into **one** such parent-private type per child. Those envelopes are **not** part of the child’s public spec — they are wiring.

## `AcceptedMessages` union

```scala
type AcceptedMessages =
  PortPluginRequest |
  GatekeeperPhaseCompleted | // parent-private; payload uses TheGatekeeper.* types
  WorkerPhaseCompleted |
  ValidatorPhaseCompleted |
  LlmStepFailed
```

**Include:**

1. Every **`### Receives`** type for **this** actor (defined in **this** file).
2. Every **internal** message type **this** actor’s `Behavior` must handle (timers, LLM adapter results, **parent-private** child-completion envelopes).
3. Do **not** copy another actor’s **`### Receives` / `### Sends`** types into this file. **Do not** add another actor’s public types bare into this union — wrap completions in **your** private case classes that **reference** the child’s types (e.g. `TheGatekeeper.Outcome`) where needed.

**Naming:** Prefer **one private wrapper per spawned child** (e.g. `GatekeeperPhaseCompleted(outcome: TheGatekeeper.Outcome)`) whose payload uses types declared under **`TheGatekeeper`**, not ad hoc duplicates of the child protocol in the parent.

## Workflow → `def` behaviors

- **`def apply(deps): Behavior[AcceptedMessages]`** — entry; delegates to the first workflow phase.
- **Numbered steps** map to **named methods**: e.g. `def running(ctx: RunContext): Behavior[AcceptedMessages]`, `def awaitingValidator(attemptsLeft: Int, ...): Behavior[AcceptedMessages]`.
- **Nested list items** (1.1, 1.2) are **inline** in the parent step or a **private def** if long — keep the parent def readable and parallel to the spec’s numbering in comments if helpful.

Example comment style:

```scala
// Workflow §4: Gatekeeper
def awaitingGatekeeper(...): Behavior[AcceptedMessages] =
  Behaviors.receiveMessage:
    case GatekeeperPhaseCompleted(outcome) => ... // outcome: TheGatekeeper.*
```

## `(Agentic Step)` and subagents

- **`Spawn the Subagent [The Gatekeeper](01-1-actor-the-gatekeeper.md)`** → `context.spawn(TheGatekeeper(deps), "gatekeeper-...")`, message child with **`TheGatekeeper.SomeReceive`** (types defined **only** under **`object TheGatekeeper`** in the gatekeeper file). Adapt outcomes into a **parent-private** wrapper (e.g. **`GatekeeperPhaseCompleted`**) that appears in the **parent’s** `AcceptedMessages` and carries **`TheGatekeeper.*`** send types as fields — do **not** add the child’s Receives/Sends ADTs to the parent object.
- Subagent specs are separate files — implement **`object TheGatekeeper`** there with **its own** `AcceptedMessages` derived from **that** spec only.
- If the step is **`(Agentic Step)`** **without** a child actor (pure LLM on this actor’s workspace), use **`LLMActor.start[O]`** per [library-api.md](library-api.md).

## Bounded loops

When the spec says “return to step N at most K times”, carry **`attemptsLeft: Int`** (or **`attempt: Int`**) in **behavior parameters** — mirror the bound exactly.

## Layout (companion object messages)

- **Tight-pack** each **sealed** family: no blank line between the `sealed trait` and its cases, nor between sibling cases of the same parent.
- **One** blank line **only** between unrelated groups (different sealed trait, or jump from messages to `type AcceptedMessages` / `def apply`).

## Helper extraction

If a workflow step needs **non-obvious** parsing, path logic, or orchestration:

- Add **`package <base>.<actorlowercase>`** (example: `com.example.getitpassing`).
- **`GetItPassing.scala`** — `object GetItPassing`.
- **`helpers.scala`** — top-level `def`s (and small pure types if needed). Split into a second helper file only when domains differ (e.g. `git.scala` vs `reporting.scala`).

Actors with **short** workflows stay as **one file** under `<pkg>/GetItPassing.scala` without a subpackage.

---

## Worked example (structural sketch)

**Spec (abbreviated):** “Get It Passing” receives `PortPluginRequest`, sends `AlreadyPorted`, `Blocked`, `PortingComplete`, `PortingFailed`; workflow spawns Gatekeeper, Worker, Validator with retries.

**Sketch** (not full — shows unions + defs). **`TheGatekeeper.GatekeeperResponse`** and the rest of the gatekeeper’s Receives/Sends live in **`TheGatekeeper.scala`** — the parent only **references** that type as a field type here.

```scala
// Spec: specs/01-0-actor-get-it-passing.md
package com.example

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*

object GetItPassing:

  sealed trait BlockerReason
  final case class LibraryBlocker(org: String, name: String, notes: String) extends BlockerReason
  final case class PluginBlocker(org: String, name: String, notes: String) extends BlockerReason
  final case class OtherBlocker(notes: String) extends BlockerReason

  final case class PortPluginRequest(
      url: Option[java.net.URL],
      localPath: Option[java.nio.file.Path],
      replyTo: ActorRef[PortPluginResponse]
  )

  sealed trait PortPluginResponse
  case object AlreadyPorted extends PortPluginResponse
  final case class Blocked(reasons: List[BlockerReason]) extends PortPluginResponse
  final case class PortingComplete(reports: List[java.nio.file.Path]) extends PortPluginResponse
  final case class PortingFailed(reports: List[java.nio.file.Path]) extends PortPluginResponse

  // Child protocols live in TheGatekeeper.scala / TheWorker.scala / … — not here.
  // Parent-only envelopes (private): reference child companions for payloads.
  private final case class GatekeeperPhaseCompleted(outcome: TheGatekeeper.GatekeeperResponse)
  // ... e.g. WorkerPhaseCompleted(...), ValidatorPhaseCompleted(...), LLM adapter wrappers

  type AcceptedMessages = PortPluginRequest | GatekeeperPhaseCompleted /* | ... */

  final class Deps(/* clones root, branch names, spawn Gatekeeper, ... */)

  def apply(deps: Deps): Behavior[AcceptedMessages] =
    Behaviors.setup: context =>
      idle(deps)

  private def idle(deps: Deps): Behavior[AcceptedMessages] =
    Behaviors.receiveMessage:
      case req: PortPluginRequest =>
        // Workflow §2–3: clone, checkout — delegate to helpers if long
        awaitingGatekeeper(deps, req, attemptsLeft = 3)
      // ...

  private def awaitingGatekeeper(
      deps: Deps,
      req: PortPluginRequest,
      attemptsLeft: Int
  ): Behavior[AcceptedMessages] =
    Behaviors.receiveMessage:
      case GatekeeperPhaseCompleted(outcome) => /* branch per spec §4; outcome from TheGatekeeper */
        Behaviors.same

end GetItPassing
```

Implement **`TheGatekeeper`**, **`TheWorker`**, **`TheValidator`** in separate files with their own **`AcceptedMessages`** derived from **their** specs’ Receives/Sends and workflow.
