# Actor spec → Scala translation

Use this when implementing **[skills/actor-harness/SKILL.md](../SKILL.md)** against specs produced by **[skills/actor-spec/SKILL.md](../../actor-spec/SKILL.md)**.

## Title → object name

- Spec heading: `# Get It Passing Actor Specification`
- Scala: `object GetItPassing` (PascalCase, strip “Actor Specification” from the title words — use the actor name phrase only).

Add `// Spec: specs/02-actor-get-it-passing.md` (or actual path) at the top of each actor `.scala` file.

## Spec format (actor-spec)

- **`specs/messages.md`** — **canonical** definitions for **all** inter-actor messages: pseudocode signatures, payloads, glosses, shared ADTs. The harness generates **`messages.scala`** from this file (not by merging full bullets from each actor spec).
- **Each actor spec → `## Receives`** — **name only**: one bullet per message type this actor accepts, e.g. `` - `PortPluginRequest` ``. **No** payloads or glosses in the actor file — those stay in **`messages.md`** only.
- **Workflow** steps that **reply** name the message and payload as in **`messages.md`** (e.g. **reply with \`Blocked(reasons)\`**). You **do not** need **`reply to [Actor](path)`** links: the recipient actor lists that message name under **`## Receives`**; **`messages.scala`** supplies the full type.

## `messages.scala` — shared protocol types

Inter-actor messages are defined **once** in **`specs/messages.md`** and compiled to **`messages.scala`** (lowercase filename, e.g. `src/main/scala/<pkg>/messages.scala`).

**Include there:**

- Every message described in **`messages.md`**, translated to **`final case class` / `case object` / `sealed trait`** as appropriate.
- Shared ADTs used in payloads (e.g. `BlockerReason` variants) — define once, reference from multiple messages.
- **`replyTo` types:** resolve from the **`messages.md`** definition (e.g. `_PortPluginOutcome_` → sealed family or union of outcome messages also listed in **`messages.md`**).

**Do not** put **`messages.scala`** content inside individual actor objects — actor files reference these types by import or by being in the same package.

## Actor `object` contents

Each **`object <ActorName>`** (in **`<ActorName>.scala`**) holds:

- **`type AcceptedMessages`** — a **Scala 3 union** of every message **name** listed in **this** actor’s spec **`## Receives`** (resolve each name to the type in **`messages.scala`**) **plus** **private/internal** messages not named in the spec (timers, **`LLMActor`** / **`messageAdapter`** wiring).
- **`Deps`**, **`def apply`**, behavior **`def`**s.
- **Private** implementation messages (e.g. `private case class RetryTick`, LLM completion wrappers) — **not** duplicated in **`messages.scala`** if they are truly internal.

## `AcceptedMessages` union

```scala
// Types PortPluginRequest, GatekeeperOutcome, … live in messages.scala (same package).
type AcceptedMessages =
  PortPluginRequest | GatekeeperOutcome | WorkerOutcome | ValidatorOutcome
```

(Add **`| …`** for this actor’s private timer / **`LLMActor`** adapter types when the spec does not name them — see [library-api.md](library-api.md).)

**Include:**

1. Every **`## Receives`** **name** for **this** actor (types from **`messages.scala`**).
2. **Internal-only** types for **this** actor when needed.

## Workflow → `def` behaviors

- **`def apply(deps): Behavior[AcceptedMessages]`** — entry; delegates to the first workflow phase.
- **Numbered steps** map to **named methods**: e.g. `def awaitingGatekeeper(...): Behavior[AcceptedMessages]`.
- **Replies:** when the spec says **reply with \`Foo(...)\`**, **`Foo`** must appear under **some** actor’s **`## Receives`** that will receive it; implement **`someActorRef ! Foo(...)`** (often **`req.replyTo`**) using the type from **`messages.scala`**. No markdown link to the recipient is required at spec level.
- **Nested list items** (1.1, 1.2) stay inline or in a **private def** if long.

Example:

```scala
// Workflow §4 — outcomes typed in messages.scala
def awaitingGatekeeper(...): Behavior[AcceptedMessages] =
  Behaviors.receiveMessage:
    case m: GatekeeperOutcome => ...
```

## `(Agentic Step)` and subagents

- **`Spawn the Subagent [The Gatekeeper](03-actor-the-gatekeeper.md)`** → `context.spawn(TheGatekeeper(deps), "gatekeeper-...")`. **`tell`** the child using **receive** types from **`messages.scala`** that match **The Gatekeeper**’s spec (e.g. **`GatekeeperRequest`**). The parent’s **`AcceptedMessages`** includes whatever **this** actor **receives back** from that child (listed in **this** actor’s `## Receives` as names → types from **`messages.md`**).
- Subagent specs are separate files — **`object TheGatekeeper`** in **`TheGatekeeper.scala`** with **its own** **`AcceptedMessages`** (subset of **`messages.scala`** + internals).
- Pure LLM on this actor (no child actor): **`LLMActor.start[O]`** per [library-api.md](library-api.md).

### `LLMActor` result type `O`

**`LLMActor.start[O](replyTo, agent, inputPrompt, outputInstructions)`** ([source](../../../agents4s-pekko/src/main/scala/agents4s/pekko/LLMActor.scala)) delivers **`O`** on success or **`LLMActor.LLMError`** on failure. **`O`** needs uPickle **`ReadWriter`** and **`JsonSchema`**.

```scala
import upickle.default.*
import upickle.jsonschema.*

case class MyStepResult(answer: String, confidence: Option[Double]) derives ReadWriter
given JsonSchema[MyStepResult] = JsonSchema.derived
```

Keep **`O`** **next to** **`LLMActor`** usage (private to the actor object) **or** in a small internal block — it is **not** part of the markdown **`## Receives`** protocol unless you also list it in a spec. See [library-api.md](library-api.md) and [`LLMActorIntegrationSpec.scala`](../../../agents4s-pekko/src/test/scala/agents4s/pekko/LLMActorIntegrationSpec.scala).

## Bounded loops

When the spec says “return to step N at most K times”, carry **`attemptsLeft: Int`** in **behavior parameters** — mirror the bound exactly.

## Layout (companion object messages)

- **Tight-pack** each **sealed** family in **`messages.scala`**: no blank line between the `sealed trait` and its cases.
- **One** blank line **only** between unrelated groups in **`messages.scala`** or between the actor object’s **`type AcceptedMessages`** and **`def apply`**.

## Helper extraction

If a workflow step needs **non-obvious** parsing, path logic, or orchestration:

- Add **`package <base>.<actorlowercase>`** (example: `com.example.getitpassing`).
- **`GetItPassing.scala`** — `object GetItPassing`.
- **`helpers.scala`** — top-level `def`s. Split only when domains differ.

---

## Worked example (structural sketch)

**Specs:** **`specs/messages.md`** defines **`PortPluginRequest`**, **`AlreadyPorted`**, **`Blocked`**, **`GatekeeperOutcome`**, … in full. **Get It Passing** lists **`PortPluginRequest`**, **`GatekeeperOutcome`**, … under **`## Receives`** (names only). **Port Plugin Client** lists **`AlreadyPorted`**, **`Blocked`**, … (names only). **`messages.scala`** is the union of **all** messages from **`messages.md`**.

**`messages.scala`** (abbreviated):

```scala
package com.example

import org.apache.pekko.actor.typed.ActorRef

// Generated from specs/messages.md — one definition each
sealed trait PortPluginOutcome
case object AlreadyPorted extends PortPluginOutcome
final case class Blocked(reasons: List[BlockerReason]) extends PortPluginOutcome
// ...

final case class PortPluginRequest(
    url: Option[java.net.URL],
    localPath: Option[java.nio.file.Path],
    replyTo: ActorRef[PortPluginOutcome],
)

sealed trait GatekeeperOutcome
// ...
```

**`GetItPassing.scala`**:

```scala
package com.example

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*

object GetItPassing:

  type AcceptedMessages = PortPluginRequest | GatekeeperOutcome | WorkerOutcome | ValidatorOutcome

  final class Deps(/* ... */)

  def apply(deps: Deps): Behavior[AcceptedMessages] =
    Behaviors.setup: context =>
      idle(deps)

  private def idle(deps: Deps): Behavior[AcceptedMessages] =
    Behaviors.receiveMessage:
      case req: PortPluginRequest =>
        awaitingGatekeeper(deps, req, attemptsLeft = 3)
      // ...

  private def awaitingGatekeeper(...): Behavior[AcceptedMessages] =
    Behaviors.receiveMessage:
      case m: GatekeeperOutcome => /* Workflow §4 */
        Behaviors.same

end GetItPassing
```

Implement **`TheGatekeeper`**, **`TheWorker`**, **`TheValidator`** in separate **`object`** files; each **`AcceptedMessages`** is a subset of **`messages.scala`** types **plus** private internals.
