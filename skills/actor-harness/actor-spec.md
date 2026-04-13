# Actor Specification

You turn the user prompt into a markdown actor specification — the specification of a Pekko Typed actor. Write the specification to the file specified by the user, or to the `specs/` folder in the workspace by default.

## Specification Folder Files

The following files are output by this workflow:

- _The Messages File_ - default `messages.md`, may be overridden by the user - is the single source of truth for all inter-actor messages definitions.
- _The Definitions File_ - default `definitions.md`, may be overridden by the user - is the single source of truth for all project-specific defined terms.
- _Actor Spec Files_ - default `01-actor-<actor-name-kebab-case>.md`, may be overridden by the user - are markdown files that contain the specification of Pekko Typed actors, one file per actor.
- _Reference Files_ - markdown files that contain arbitrary content relevent to the specification such as special knowledge, documentation etc.
- _Index File_ - `INDEX.md`, is a markdown file that contains a list of all the actor specification files.

## The Actor Specification Format

The Actor Specification is a markdown file that contains the specification of a Pekko Typed actor. Each actor spec is at most 100 lines. You must use precise language with zero omissions, zero generalizations, zero ambiguity. If you are using a project-specific term, it must be defined in the _Definitions File_. If you are mentioning a message, it must be defined in the _Messages File_.

It is structured as follows:

```markdown
# <Actor Name> Actor Specification

## Actor Purpose

One paragraph describing the purpose of the actor.

## Receives

- `MessageName`
- `AnotherMessage`
...

## Workflow

1. Step 1
2. Step 2
...
```

The following are the rules you should follow for each section:

### Actor Purpose
- It is a short, high-level paragraph describing the purpose and intent of the job the actor is supposed to do.

### Receives
- It is a list of message names that the actor can receive.
- This list contains only the message names, no definitions, no payloads.
- Each message name must match a message defined in the _Messages File_.

### Workflow
- It is a numbered, nested list of steps describing the workflow of the actor, with at most two levels of nesting; if you need more, flatten the steps or delegate to a child actor.
- Each step is declarative, must be precise and unambiguous.
- If a step is supposed to be executed by an LLM Agent, it should be marked with `(Agentic Step)`.
- If a step is complex and involves work the details of which are not relevant to the actor's purpose, it should be delegated to a child actor. After delegation, this actor will expect a reply message from the child actor.
- When a step has different outcomes, spell out each branch explicitly, as a nested list item.
- Results of the actor's work are to be communicated back to the requesting actor using a pattern: "Reply to the requesting actor with <reply message name>". The reply message name must be a message defined in the _Messages File_. The target actor must list the reply message name in its _Receives_ section.
- Project-specific concepts and terms must be marked with `_Term_` and must be listed in the _Definitions File_.

Delegation to a child actor follows the specific pattern:

```markdown
1. Spawn the Subagent [Name](path/to/spec.md). It will do <work description in one sentence> and reply with <all possible reply message names>.
    1. On <reply message name>, do X
    2. On <reply message name>, do Y
```

When a subagent is used in this way, it must be defined as a separate _Actor Specification File_ in the `specs/` folder, following all the rules in this specification. Child actor specification must not leak into the parent actor specification.

## The Messages File Format

The _Messages File_ is a markdown file that contains the definitions of all the messages that the actor system uses. It is structured as follows:

```markdown
# Messages

- `MessageName(payload1: PayloadType1, payload2: PayloadType2, ...)` - What this message does.
- `AnotherMessage(payload1: PayloadType1, payload2: PayloadType2, ...)` - What this message does.
```

Follow the following rules for the Message File Format:

- Each message is exactly one list line, following the exact format specified above.
- No line break between the signature and its description.
- The messages are intentionally specified as pseudocode - do not attempt to write real code in the Messages File.
- The description is a few sentences at most, prefer short descriptions. The purpose of the description is to convey the intent of the message concisely.

## The Index File Format

The _Index File_ is a markdown file that contains a list of all the actor specification files. It is structured as follows:

```markdown
# Index

File Name | File Type
--------- | ---------
`messages.md` | Messages File
`definitions.md` | Definitions File
`actors/actor1.md` | Actor Specification File
`actors/actor2.md` | Actor Specification File
`references/special-knowledge.md` | Reference File
`references/documentation.md` | Reference File
...
```

- File Name is the path to the file relative to the `specs/` folder.
- File Type is the type of the file, one of the following: Messages File, Definitions File, Actor Specification File.
- Reference Files are files that contain arbitrary content relevent to the specification such as special knowledge, documentation etc.

Apart from the table of the files listed above, the Index File may also contain arbitrary rules and conventions applicable to the specification files. These rules and conventions may override the rules specified in this specification.

## Rules for Writing Specifications

- Each actor should do one specific job. Split complex jobs into multiple actors.
- Write in second person ("Your purpose is to...").