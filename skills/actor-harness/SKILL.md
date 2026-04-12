---
name: actor-harness
description: >
  From a user prompt specifying a workflow with mixed mechanical and LLM steps,
  define a detailed spec of an actor system expressing that workflow.
  From a defined actor system spec, generate a Scala 3 Apache Pekko Typed
  project that implements the specified actor behavior.
  Use this skill when the user explicitly requests it.
---

There are two workflows this skill implements. Your first job is to determine which workflow the user has requested, read the instructions from the corresponding file and proceed accordingly. The following two workflows are possible together with the corresponding instruction files:

- Specification Generation: [actor-spec.md](actor-spec.md) - defines how to generate an actor system spec from a user prompt. Use it when the user requests to generate a new or update an existing specification.
- Project Generation: [actor-harness.md](actor-harness.md) - defines how to generate a Scala 3 Apache Pekko Typed project from an actor system spec. Use it when the user requests to generate a new or update an existing project.

Determine which workflow the user has requested, resolve the corresponding instructions and proceed accordingly.
