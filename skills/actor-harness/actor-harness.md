You turn [Actor Specification](actor-spec.md) files into a Scala 3 Apache Pekko Typed project that defines the actor system implementing the specified behavior.

Follow the steps to do it:

1. Scaffold the Scala project following the [Project Boilerplate](references/project-boilerplate.md) instructions.
2. Read the `INDEX.md` file to discover the list of [Specification Files](actor-spec.md).
3. For each specification file discovered in the previous step, create the Scala source files to translate the entities discovered in the specification file into Scala code. Use _Definitions File_ and _Reference Files_ to disambiguate the meaning of terms and for extra context. Follow the _Actor Translation Guide_ below.

# Actor Translation Guide

The _Actor Translation Guide_ is a set of instructions that guide you through the process of translating the [Actor Specification Files](actor-spec.md) into Scala source files.

## Principles

Follow these principles during translation process:

- Conciseness and minimalism.
  - Aim for the smallest possible file size.
  - No unnecessary defensive programming, wrappers or other artifacts not explicitly defined by the spec.
  - Aim for reusing existing code rather than redefining it.
  - If a certain code pattern repeats, it must be abstracted into a function, given a name descriptive to its intent.
  - Avoid elaborate CLI logic such as parsing command line arguments etc. Entry points do not take any parameters. Their input values are hardcoded at the start of the main files as constants. Populate them with dummy parameters.
- Declarative, functional style.
  - Your functions must read like specification itself, consisting of calls to smaller functions with descriptive names.
  - Aim for small function size, up to 20 lines of code each.
  - Avoid deeply nested control structures like `if` or `match`. Up to two levels of nesting is allowed.

## The Messages File Translation

_The Message File_ translates to a `domain.scala` file in the `src/main/scala/` folder and contains:

- Every message described in the message-definitions file, translated to `case class` / `case object` / `sealed trait` / `enum` as appropriate.
- Shared ADTs used in payloads (e.g. `BlockerReason` variants)

If the actor system is large and compartmentalized into several independent actor clusters, each cluster may have its own _Messages File_.

## The Actor Specification File Translation

Each _Actor Specification File_ translates to:

- _Actor Package_. A Scala package named after the actor under the `src/main/scala/` folder. The package name is the actor name in camelCase.
- _Actor File_. A Scala file `<ActorName>.scala` named after the actor containing the behavior of the actor itself.
- _Actor Main Object_. A Scala `main` object `<ActorName>Main.scala` under the package containing a standalone, sandbox entry point to test the actor against a sample input.
- _Actor Domain File_. Optionally, an actor-specific domain file `domain.scala` under the actor's package containing internal ADTs. These are NOT used for inter-actor communications, only for internal actor state and for communication with the ephemeral child LLM actors.
- _API Files_. If an actor performs side effects, those go under a separate `api` package as a separate object injected into the actor's constructor as a dependency.
- _Prompts Files_. Any prompts for LLM actors go under the `src/main/resources/prompts/` folder.
- _Test FIles_ testing the actor's control flow decoupled of the child actors and API dependencies. Separate tests are implemented to also test any concrete API implementations of the abstract API injected into the actor's constructor.

## The ActorName.scala File

`ActorName.scala` contains:

```scala
type AcceptedMessages = Msg1 | Msg2  // The union of all messages the actor can receive.

trait ActorName:
  def apply(inputParameters...): Behavior[AcceptedMessages]

class ActorNameImpl(api1: Api1, api2: Api2, ...) extends ActorName:
  def apply(inputParameters...): Behavior[AcceptedMessages] = ...
  private def privateBehavior1(inputParameters...): Behavior[AcceptedMessages] = ...
  private def privateBehavior2(inputParameters...): Behavior[AcceptedMessages] = ...
```

- `type AcceptedMessages` — a Scala 3 union of message listed in this actor’s spec `## Receives` and defined in the `domain.scala` file, plus private/internal messages not named in the spec.
- `trait ActorName` - the public API of the actor defining the signature of its entry point behavior.
- `class ActorNameImpl(api1: Api1, api2: Api2, ...) extends ActorName` - the implementation of the actor's public API. The constructor takes any dependencies that encapsulate side-effecting behavior. The actor only defines control flow, it does not hard-code any side-effecting behavior, including spawning child actors - it accesses such behavior through the dependencies (e.g. `fileSystemApi.createFile(inputParameters...)` or `anotherActor()`).
- `def apply(inputParameters...): Behavior[AcceptedMessages]` - the starting behavior of the actor accepting any inputs it may need to start its work.
- Other behavior `def`s as needed, returning `Behavior[AcceptedMessages]`.

**Behaviors**. The behavior `def`s must closely follow the specification - aim for each `def` to map to a workflow specification step, and be named descriptively after the specification step, to achieve declarative style. Aim for the total size of one `def` to be no more than 7 lower-level operations, abstracting to separate functions as needed. As the agent will be changing its behavior over its lifetime, it is expected that the workflow specified in the _Actor Specification File_ will be split over several behavior `def`s.

The behaviors are defined using `Behaviors.receive`, `Behaviors.receiveMessage`, `Behaviors.setup`, `Behaviors.receivePartial`, `Behaviors.withTimers` and other `Behaviors` methods. For the reference on how to define behaviors, see the [Behaviors API](https://pekko.apache.org/api/pekko/current/org/apache/pekko/actor/typed/scaladsl/Behaviors$.html).

**(Agentic Step)** steps are expressed via `agents4s.pekko.LLMActor` backed by `agents4s.cursor.CursorAgent` — see [library-api.md](references/library-api.md) for wiring, prompts, and the **`JsonSchema`/`ReadWriter` pattern** for structured replies. For such steps, start a new `LLMActor`, supply a prompt, and expect a reply with the step results. Store prompts under `src/main/resources/prompts/` and load with `agents4s.prompt.PromptTemplate.load`. Spawning LLM actors obeys the API dependency injection rules and must never be hard-coded: instead, create a separate API trait defining the interface for spawning an LLM actor and inject it into the actor's constructor.

**Spawn a child actor** steps are expressed via `context.spawn` spawning the child actor and expecting it to reply with a message. The child actor must be defined as a separate _Actor Specification File_ in the `specs/` folder, following all the rules in this specification. Child actor specification must not leak into the parent actor specification. Spawning of a child actor obeys the API dependency injection rules and must never be hard-coded: instead, inject the respective child actor's behavior-defining `trait` as a constructor parameter into the parent actor and use it to spawn the child actor: `context.spawn(childActor(), childActorName)`.

**Inter-agent Communication** should be done by sending messages between actors using the `recipient ! message` pattern. Afterwards, if the sender is expecting a reply, it must change its behavior to be able to handle such a reply by returning a `Behavior[AcceptedMessages]` from the current behavior `def`.

## The Prompts

The prompts for every agentic step must be self-contained and must not rely on the specs folder being present in context. All the relevant context must be provided in the prompt. Specifically, make sure to provide the same information in the prompt as provided in the agentic step, the context information about the bigger workflow from the _Actor Specification File_, references to external resources etc. All project-specific definitions must be provided in the prompt.

## API Files

Actor File only defines the control flow of the actor in its behaviors - never the actual details of how to perform the side effects. Side effects are implemented in separate API files. Examples of side effects include:

- File system, Network, Database operations, etc.
- Spawning child actors, including LLM actors.

Any such operations must be implemented under a separate `api` package shared by all actors. Each logical domnain gets its own API file. An API file consists of:

```scala
trait ApiExample:
  def operation1(input: InputType): OutputTyped
  def operation2(input: InputType): OutputTyped
  ...
end ApiExample

object ApiExampleImpl extends ApiExample:
  def operation1(input: InputType): OutputTyped = ...
  def operation2(input: InputType): OutputTyped = ...
  ...
```

The traits are injected into the actor's implementation constructor as a dependency.

## Main Object

The `main` object under the package contains a standalone, sandbox entry point to test the actor against a sample input. It is minimal, up to 50 lines of code, its job is to start a new Actor system holding a single actor instance, send it a sample input and print the result. A Main object consists of the wiring of concrete API implementations into this actor's concrete implementation, and the spawning of the actor itself:

```scala
object ActorNameMain:

  // User-facing inputs parameterize the task performed by the actor, e.g. file path to work against
  private val UserFacingInput1 = ...
  private val UserFacingInput2 = ...

  @main def run(): Unit =
    val childActor1 = ChildActor1Impl(ApiImpl1, ApiImpl2, ...)
    val childActor2 = ChildActor2Impl(ApiImpl1, ApiImpl2, ...)
    val actorName = ActorNameImpl(ApiImpl1, ApiImpl2, childActor1, childActor2)

    def clientBehavior(ctx: ActorContext[ReviewOutcome]): Behavior[ReviewOutcome] =
      Behaviors.receiveMessage[ActorNameOutputMessage]: m =>
        println(s"Received output message: $m")
        Behaviors.stopped

    val system = ActorSystem(
      Behaviors.setup[Unit] { ctx =>
        val client: ActorRef[ActorNameOutputMessage] = ctx.spawn(clientBehavior(ctx), "client")
        val actorNameRef = ctx.spawn(actorName(UserFacingInput1, UserFacingInput2), "actor-name")
        actorNameRef ! ActorNameInputMessage(client)
        Behaviors.empty
      },
      "actor-name-sandbox"
    )
    system.terminate()
    Await.ready(system.whenTerminated, 60.seconds)
```

This object does NOT contain any elaborate user interfaces, such as command line arguments parsing, or any other complex logic. It is minimal, happy-path-focused, any inputs are hardcoded at the start of the object as constants.

## Testing
### Actor Tests

Follow [Pekko Testing Guidelines](https://pekko.apache.org/docs/pekko/current/typed/testing-async.html) to implement your Actor tests. The objective of Actor tests is to test the control flow of the actor in separation from the child actors and any API dependencies it may have. The tests must ensure the actor follows the _Actor Specification File_ exactly. This means testing the actor's happy path and any possible error paths as defined by the specification.

To implement Actor tests, follow the guidelines below:

- Use `org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit` to write your Pekko ScalaTest specs, one spec per actor.
- Mock the actor dependency APIs by extending their API traits and implementing the mock operations as needed to fully test the control flow of the actor.
- Mock the child actors to be no-op actors returning `Behaviors.same` for all messages. Pekko Typed actors are sender-agnostic when they receive a new mesasge - they do not know who it comes from. Therefore, you do NOT need the mock child actors to send any messages to the tested actor - this is handled by the test kit.
- Each test case is one path through the actor's control flow. To simulate the path, send an appropriate sequence of messages to the actor, simulating any external actor requests or actor's own child actor responses as needed.

Here is an example of a test case for the actor:

```scala
class ReviewManagerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  private val noOpChild1: ChildActor1 = new ChildActor1:
    def apply(m: String): Behavior[ChildActor1Inbox] =
      Behaviors.receiveMessage(_ => Behaviors.same)

  private val noOpChild2: ChildActor2 = new ChildActor2:
    def apply(m: String): Behavior[ChildActor2Inbox] =
      Behaviors.receiveMessage(_ => Behaviors.same)

  private val mockApi1: Api1 = new Api1:
    def operation1(input: InputType): OutputTyped = throw new UnsupportedOperationException
    def operation2(input: InputType): OutputTyped = // detect method was called as needed
    def operation3(input: InputType): OutputTyped = ...

  private val mockApi2: Api2 = new Api2:
    def operation1(input: InputType): OutputTyped = throw new UnsupportedOperationException
    def operation2(input: InputType): OutputTyped = // detect method was called as needed
    def operation3(input: InputType): OutputTyped = ...

  private def spawnActorName(): ActorRef[AcceptedMessages] =
    spawn(
      ActorNameImpl(mockApi1, mockApi2, noOpChild1, noOpChild2)
    )

  "ActorName" should {
    "path 1" in {
      val actorNameRef = spawnActorName()
      actorNameRef ! ActorNameInputMessage()
      actorNameRef ! ChildResponse1()
      actorNameRef ! ChildResponse2()
      ...
      testKit.expectMessage(ExpectedOutputMessage())
    }
    ...
  }
```

### API Tests

API tests are implemented to test concrete API implementations in isolation from the rest of the system. They are implemented as simple ScalaTest test suites that test all the possible interaction scenarios for each API method. Since the tested behavior is side-effecting, the tests must be properly sandboxed as to not affect live data - e.g. run in a temporary directory, with a temporary environement etc. Here is an example of how they tests may look like:

```scala
class ApiSpec1 extends AnyWordSpecLike with Matchers:

  private def withSandboxEnvironment[T](f: SandboxEnvironment => T): T =
    val env = setupSandboxEnvironment()
    try f(env)
    finally cleanupSandboxEnvironment(env)

  "Api1" should {
    "operation 1" in {
      withSandboxEnvironment { env =>
        val api = Api1Impl(env)
        api.operation1(InputType()) should be(OutputType())
      }
    }
  }
```
