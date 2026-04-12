---
name: actor-spec
description: >
  Create a markdown actor specification from the user prompt. Edit existing actor specifications to conform to the best practices outlined in this skill.
---

# Actor Specification

You turn the user prompt into a markdown actor specification, precisely the specification of a Pekko Typed actor. You will write the specification to the file specified by the user, or `specs/` folder in the workspace by default.

The Actor Specification follows the pattern:

```markdown
# <Actor Name> Actor Specification

## Actor Purpose

The purpose of the actor.

## Messaging Protocol

Describes what messages the actor can receive from non-child actors and what messages it can send back.

## Workflow

A numbered, nested list of steps describing the workflow of the actor.
```

## The Specification Format
The specification should always start with the header `# <Actor Name> Actor Specification`. The purpose should be a single paragraph describing the actor's purpose.

The messaging protocol should consist of two parts: what messages the actor can receive from non-child actors and what messages it can send back. Only include the message types that are exchanged with the external actors - do not include the messages exchanged with the child actors this actor may spawn.

The messages in the messaging protocol are defined as pseudocode in the format `MessageName(payload1: PayloadType1, ..., PayloadN: PayloadTypeN)`. `MessageName` should describe the intent of the message in at-most 5 words. Payload types describe what data the message carries for the actor to work on. Do not write the messags as fully-fledged Scala code. Each message should be followed by a short description (one paragraph at most) describing the intent of the message.

The Workflow is a numbered, nested list (up to two levels of nesting) describing the steps the actor follows when handling a message. The steps should be concrete and unambiguous. If any project-specific term is used, it should be defined either in the Actor's specification in a separate section, or in a dedicated definitions file within the wider suite of specification files, if such file exists or supplied explicitly by the user.

Each step of the workflow should be chunked and scoped to the purpose of the actor. If a step that needs to be taken is too complex and involves multiple sub-steps, it should be delegated to a child actor. However if the step involves only a few substeps that aren't too complex, consider describing them as a substeps of this step without delegating to a child actor. If a step involves calling an LLM Agent, it should also be delegated to a child actor. If in doubt, ask the user for clarification regarding the desired step chunk size.

A workflow step may spawn a child actor to delegate work to it. An actor-spawning step follows the following pattern:

```markdown
1. Spawn the Subagent [Subagent Name](path/to/subagent/specification.md), which does X.
    1. Subagent response 1 handling
    2. Subagent response 2 handling
    3. Subagent response N handling
```

Subagent Name is the name of the subagent and the path is the path to the subagent's specification file relative to the specification folder. Defining a subagent step means you also need to define the specification of that subagent at the path specified. Do not specify the subagent's behavior, workflow or messaging protocol in the parent actor's specification. You may have one line describing at a high-level what the subagent does, however, all the specification should be contained in the subagent's specification file.

## Rules for Writing Specifications

- Keep it concise - no longer than 100 lines.
- Each actor should do one specific job. Split complex jobs into multiple actors.
- Use precise language - avoid generalizations, omissions, ambiguity.
