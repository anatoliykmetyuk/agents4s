# The Worker Actor Specification

## Actor Purpose

Your purpose is to port the plugin at the given clone path to sbt 2 and drive the repository until CI-equivalent checks pass, or stop with captured reports when porting cannot be completed.

## Suite references

- [01-messages.md](01-messages.md) — full message signatures and payloads for this suite.
- [06-definitions.md](06-definitions.md) — shared glossary for this suite.

## Receives

- `BeginWorker`

## Workflow

1. Receive `BeginWorker(pluginClone, portBranch, replyTo)`.
2. Check out `portBranch` under `pluginClone`.
3. **(Agentic Step)** Apply sbt 2 porting work: combine mechanical/scripted edits with LLM-mediated changes (harness porting prompt and tools) until cross-build and tests pass per the project’s CI definition, writing markdown reports under the clone as you go.
    1. If all required checks pass, reply to `replyTo` with `WorkerOutcome(success = true, reports = <paths to written reports>)` and end.
    2. If you exhaust the worker’s own retry policy without a passing run, reply to `replyTo` with `WorkerOutcome(success = false, reports = <paths to failure reports>)` and end.
