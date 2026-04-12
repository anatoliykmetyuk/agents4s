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

- **Private/internal** messages for **this** actor only: timers, **`messageAdapter`** targets for **`LLMActor`**, etc. Example: `private case object RetryTick`.
- **LLM completion:** after `messageAdapter`, map **`O | LLMActor.LLMError`** into types you include in **`AcceptedMessages`** (often small **private** case classes **only** for LLM wiring — not for child actors; see below).

### Where each actor’s protocol lives

- **`### Receives`** and **`### Sends`** for actor **A** are **`final case class` / `sealed trait`** definitions in **`object A`** in **`A.scala`** (that actor’s file). **Never** re-declare another actor’s messages in a parent file.
- **Child actors:** the same — all of **`TheGatekeeper`**’s protocol types live under **`object TheGatekeeper`** in **`TheGatekeeper.scala`**.
- **Parent handling child traffic:** the parent **`AcceptedMessages`** union includes the **actual** message types **declared on the child** (the messages the child **`tell`**s upward — that spec’s **`### Sends`** / internal-to-child types as you implement them). **Import** those types from the child companion (e.g. **`import TheGatekeeper.GatekeeperResponse`**) or refer to them as **`TheGatekeeper.GatekeeperResponse`**. **Do not** introduce a parallel **`private final case class …`** in the parent to “wrap” a child message — that duplicates the protocol.

## `AcceptedMessages` union

```scala
// Child Sends types are defined only in TheGatekeeper.scala / TheWorker.scala / …
type AcceptedMessages =
  PortPluginRequest |
  TheGatekeeper.GatekeeperResponse |
  TheWorker.WorkerDone |
  TheValidator.ValidatorOutcome
```
(Add **`| …`** for this actor’s private timer / **`LLMActor`** adapter types when the spec does not name them — see [library-api.md](library-api.md).)

**Include:**

1. Every **`### Receives`** type for **this** actor (defined in **this** file).
2. Every **child message type** this parent must **`receiveMessage`** when a child **`tell`**s it — use the **types from the child’s companion** (import or FQCN), **not** new parent-local copies.
3. **Internal-only** types for **this** actor (timers, LLM adapter mapping) when the spec does not name them.

**Do not** define **`GatekeeperDone`**, **`GatekeeperPhaseCompleted`**, or similar **in the parent** if those names belong to the child’s protocol — they belong under **`object TheGatekeeper`** and are **imported** into the parent.

## Workflow → `def` behaviors

- **`def apply(deps): Behavior[AcceptedMessages]`** — entry; delegates to the first workflow phase.
- **Numbered steps** map to **named methods**: e.g. `def running(ctx: RunContext): Behavior[AcceptedMessages]`, `def awaitingValidator(attemptsLeft: Int, ...): Behavior[AcceptedMessages]`.
- **Nested list items** (1.1, 1.2) are **inline** in the parent step or a **private def** if long — keep the parent def readable and parallel to the spec’s numbering in comments if helpful.

Example comment style:

```scala
// Workflow §4: Gatekeeper — match on types from TheGatekeeper (import or TheGatekeeper.*)
def awaitingGatekeeper(...): Behavior[AcceptedMessages] =
  Behaviors.receiveMessage:
    case m: TheGatekeeper.GatekeeperResponse => ...
```

## `(Agentic Step)` and subagents

- **`Spawn the Subagent [The Gatekeeper](01-1-actor-the-gatekeeper.md)`** → `context.spawn(TheGatekeeper(deps), "gatekeeper-...")`; **`tell`** using **`TheGatekeeper`**’s **`### Receives`** types. When the child replies to the parent, it **`tell`**s **`TheGatekeeper`**’s **`### Sends`** (or workflow-internal types you placed under **`object TheGatekeeper`**). The parent’s **`AcceptedMessages`** includes those types via **`import TheGatekeeper.…`** / **`TheGatekeeper.*`** — **not** new case classes in the parent object.
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

**Sketch** (not full — shows unions + defs). **`TheGatekeeper.GatekeeperResponse`** (and other gatekeeper messages) are **`import TheGatekeeper.*`** / FQCN here — **defined only** in **`TheGatekeeper.scala`**.

```scala
// Spec: specs/01-0-actor-get-it-passing.md
package com.example

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import TheGatekeeper.GatekeeperResponse

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

  // No duplicate child messages here — import TheWorker.…, TheValidator.… as needed.
  type AcceptedMessages = PortPluginRequest | GatekeeperResponse /* | TheWorker.WorkerDone | … */

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
      case m: GatekeeperResponse => /* branch per spec §4 */
        Behaviors.same

end GetItPassing
```

Implement **`TheGatekeeper`**, **`TheWorker`**, **`TheValidator`** in separate files with their own **`AcceptedMessages`** derived from **their** specs’ Receives/Sends and workflow.
