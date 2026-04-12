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

These specifications are the primary input to the **harness** skill, which translates each actor spec into a runnable Scala 3 Pekko Typed actor. The harness maps messages to sealed trait hierarchies, workflow steps to behavior logic, and subagent-spawning steps to child actor lifecycles. A well-written spec produces a clean actor with minimal manual fixup; a vague or inconsistent spec forces rework. Keep this downstream translation in mind — be concrete enough that someone (or a tool) reading the spec can produce the actor without guessing.

## The Specification Format

The specification follows this structure:

```markdown
# <Actor Name> Actor Specification

## Actor Purpose

The purpose of the actor.

## Messaging Protocol

Describes what messages the actor can receive from non-child actors and what messages it can send back.

## Workflow

A numbered, nested list of steps describing the workflow of the actor.
```

The specification should always start with the header `# <Actor Name> Actor Specification`. The purpose should be a single paragraph describing the actor's purpose. Write in second person ("Your purpose is to...") — these specs read as instructions to the actor, and the second-person voice makes intent unambiguous both for human readers and for LLM agents that may implement the actor's agentic steps.

## Messaging Protocol

The messaging protocol should consist of two parts: what messages the actor can receive from non-child actors and what messages it can send back. Only include the message types that are exchanged with the external actors — do not include the messages exchanged with the child actors this actor may spawn.

The messages in the messaging protocol are defined as pseudocode in the format `MessageName(payload1: PayloadType1, ..., PayloadN: PayloadTypeN)`. `MessageName` should describe the intent of the message in at most 5 words. Payload types describe what data the message carries for the actor to work on. Do not write the messages as fully-fledged Scala code. Each message should be followed by a short description (one paragraph at most) describing the intent of the message.

## Workflow

The Workflow is a numbered, nested list (up to two levels of nesting) describing the steps the actor follows when handling a message. The steps should be concrete and unambiguous.

Each step of the workflow should be chunked and scoped to the purpose of the actor. If a step that needs to be taken is too complex and involves multiple sub-steps, it should be delegated to a child actor. However if the step involves only a few substeps that aren't too complex, consider describing them as substeps of this step without delegating to a child actor. If a step involves calling an LLM Agent, it should also be delegated to a child actor. If in doubt, ask the user for clarification regarding the desired step chunk size.

### Workflow patterns

The following patterns appear frequently in well-written workflows. Read [examples/01-0-actor-get-it-passing.md](examples/01-0-actor-get-it-passing.md) for a complete illustration showing all of them in context.

**Defined terms.** When a workflow references a project-specific concept (a directory, a branch name, a configuration value), introduce it as an italicized defined term on first use — e.g., _Plugin Clones Directory_, _Default Branch_. This makes the spec self-contained and lets the reader (or the harness) know exactly what the term refers to. Define terms either in the actor's specification in a separate section, or in a dedicated definitions file within the wider suite of specification files, if such file exists or is supplied explicitly by the user.

**Concrete commands.** When a step involves a specific shell command, include it inline in backticks — e.g., "Check it out via `git checkout <_Default Branch_>`." This removes ambiguity about what "update the branch" means in practice.

**Conditional branching.** When a step has different outcomes, spell out each branch explicitly: "If it has cleared the porting, proceed with the next steps. If it has denied porting, stop the workflow here and report to the requesting agent with a corresponding message according to your messaging protocol."

**Bounded loops.** When a step can be retried, state the bound: "go back to step 6... It is possible to return to step 6 no more than 3 times." Without an explicit bound, the harness cannot know when to give up.

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
