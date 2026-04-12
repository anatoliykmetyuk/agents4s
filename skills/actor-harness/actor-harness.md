You turn [Actor Specification](actor-spec.md) files into a Scala 3 Apache Pekko Typed project that defines the actor system implementing the specified behavior.

Follow the steps to do it:

1. Scaffold the Scala project following the [Project Boilerplate](references/project-boilerplate.md) instructions.
2. Discover actor specs, message definitions, and term definitions. Read the `specs/` folder recursively, locating the _Messages File_, the _Definitions File_ and the _Actor Specification_ files by looking at their first line headers and matching them to the [Actor Specification](actor-spec.md) format.
3. For each actor specification file, create the Scala source files to translate the entities discovered in the previous step into Scala code. Use _Definitions File_ to disambiguate the meaning of terms used through out the spec. Follow the _Actor Translation Guide_ below.

## Actor Translation Guide

The _Actor Translation Guide_ is a set of instructions that guide you through the process of translating the [Actor Specification Files](actor-spec.md) into Scala source files.

### Principles

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

### The Messages File Translation

_The Message File_ translates to a `messages.scala` file in the `src/main/scala/` folder and contains:

- Every message described in the message-definitions file, translated to `case class` / `case object` / `sealed trait` / `enum` as appropriate.
- Shared ADTs used in payloads (e.g. `BlockerReason` variants)

### The Actor Specification File Translation

Each _Actor Specification File_ translates to:

- A Scala package named after the actor under the `src/main/scala/` folder. The package name is the actor name in camelCase.
- A Scala file containing an `object <ActorName>` with the actor's behavior under the package.
- A Scala `main` object under the package containing a standalone, sandbox entry point to test the actor against a sample input.
- Optionally, a helper file `helpers.scala` under the package containing helper functions for the actor.

Each `object <ActorName>` (in `<ActorName>.scala`) holds:

- `type AcceptedMessages` — a Scala 3 union of message listed in this actor’s spec `## Receives` and defined in the `messages.scala` file, plus private/internal messages not named in the spec.
- Any internal messages private to the actor, such as timer messages.
- `def apply(inputParameters...): Behavior[AcceptedMessages]` - the starting behavior of the actor accepting any inputs it may need to start its work.
- Other behavior `def`s as needed, returning `Behavior[AcceptedMessages]`.

**Behaviors**. The behavior `def`s must closely follow the specification - aim for each line of such `def` to be a single step as defined in the specification. Abstract such steps into separate functions defined in the object and named after the specification steps to achieve declarative style. Aim for the total size of such a `def` to be on the order of the number of steps it implements.

As the agent will be changing its behavior over time, it is expected that the workflow specified in the _Actor Specification File_ will be split over several behavior `def`s.

The behaviors are defined using `Behaviors.receive`, `Behaviors.receiveMessage`, `Behaviors.setup`, `Behaviors.receivePartial`, `Behaviors.withTimers` and other `Behaviors` methods. For the reference on how to define behaviors, see the [Behaviors API](https://pekko.apache.org/api/pekko/current/org/apache/pekko/actor/typed/scaladsl/Behaviors$.html).

**`(Agentic Step)`** steps are expressed via `agents4s.pekko.LLMActor` backed by `agents4s.cursor.CursorAgent` — see [library-api.md](references/library-api.md) for wiring, prompts, and the **`JsonSchema`/`ReadWriter` pattern** for structured replies. For such steps, start a new `LLMActor`, supply a prompt, and expect a reply with the step results. Store prompts under `src/main/resources/prompts/` and load with `agents4s.prompt.PromptTemplate.load`.

**Spawn a child actor** steps are expressed via `context.spawn` spawning the child actor and expecting it to reply with a message.

**Inter-agent Communication** should be done by sending messages between actors using the `recipient ! message` pattern. Afterwards, if the sender is expecting a reply, it must change its behavior to be able to handle such a reply by returning a `Behavior[AcceptedMessages]` from the current behavior `def`.

### Helper File

If a workflow step needs technical, domain-specific logic, implement it as functions in the `helpers.scala` file.

### Main Object

The `main` object under the package contains a standalone, sandbox entry point to test the actor against a sample input. It is minimal, up to 50 lines of code, its job is to start a new Actor system holding a single actor instance, send it a sample input and print the result.
