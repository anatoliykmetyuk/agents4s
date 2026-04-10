# Pekko Typed actors — patterns for harness code

Use Scala 3 and **Apache Pekko Typed**. Each **actor** is a Scala `class` (or `object`-backed factory) with a **companion object** that holds **all input and output message types** for that actor. Elsewhere refer to them as `ActorName.MessageName` (e.g. `Orchestrator.StartWork`, `WorkerA.InspectRequest`).

## Why companion-object messages

- One place to read an actor’s contract.
- No separate “global protocol” package that grows unbounded.
- Cross-actor types stay explicit: `WorkerA.InspectResponse` in `Orchestrator` code documents the dependency.

## Minimal skeleton

```scala
// Spec: specs/01-1-worker-a.md
package com.example

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*

object WorkerA:

  /** All messages this actor accepts (sealed avoids exhaustiveness mistakes). */
  sealed trait Command
  final case class InspectRequest(path: java.nio.file.Path, replyTo: ActorRef[InspectResponse])
      extends Command

  /** Responses sent to `replyTo` from InspectRequest */
  sealed trait InspectResponse
  case object AlreadyDone extends InspectResponse
  final case class NeedsWork(details: String) extends InspectResponse

  /** Internal async completion (example) — not part of public API */
  private final case class InspectCompleted(response: InspectResponse, replyTo: ActorRef[InspectResponse])
      extends Command

  def apply(deps: WorkerADeps): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case InspectRequest(path, replyTo) =>
          // mechanical work + maybe ask LlmBridge; then reply
          replyTo ! AlreadyDone
          Behaviors.same
        case InspectCompleted(response, replyTo) =>
          replyTo ! response
          Behaviors.same
      }
    }

end WorkerA

final class WorkerADeps(
    // ports, config, etc.
)
```

Factory entrypoint: `WorkerA.apply` returns `Behavior[WorkerA.Command]`. Spawn with `context.spawn(WorkerA(deps), "worker-a")`.

## Reply-to pattern

Requests that need an answer carry `replyTo: ActorRef[Response]` (see `InspectRequest` above). The type parameter of `Behavior` is usually the **command** sum type; responses are a **separate** sealed hierarchy in the same companion object.

## Spawning children

```scala
val child: ActorRef[WorkerA.Command] =
  context.spawn(WorkerA(deps), "worker-a")
child ! WorkerA.InspectRequest(path, context.messageAdapter(identity)) // or explicit reply probe
```

Use `messageAdapter` / `adaptMessage` when the parent must map child responses into its own `Command` type.

## State machines

Return a different `Behavior` from a branch when the actor needs phases (e.g. idle vs waiting for LLM):

```scala
def running(attemptsLeft: Int): Behavior[Command] =
  Behaviors.receiveMessage {
    case r: ValidateRequest if attemptsLeft <= 0 => ...
    case r: ValidateRequest => validating(r, attemptsLeft)
  }
```

## Retries (bounded)

Keep retry count in **behavior parameters** or small immutable state class; on `Rejected` from a validator, spawn a new worker pass or resend message until cap — mirror the spec’s numeric limit.

## Blocking and dispatchers

Do **not** block the actor thread on `CursorAgent` or disk-heavy loops. Offload blocking work to `Future` on a **blocking** dispatcher and use `pipeToSelf` (see [llm-bridge-guide.md](llm-bridge-guide.md)).

## Lifecycle (optional)

`Behaviors.supervise(...)` for restart strategy; `PreRestart`/`PostStop` via `Behaviors.setup` + `context.watch` / signal adapters when you need cleanup of external resources.
