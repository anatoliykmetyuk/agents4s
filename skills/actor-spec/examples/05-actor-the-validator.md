# The Validator Actor Specification

## Actor Purpose

Your purpose is to verify independently that a plugin clone satisfies post-port checks after a porting attempt.

## Suite references

- [01-messages.md](01-messages.md) — full message signatures and payloads for this suite.
- [06-definitions.md](06-definitions.md) — shared glossary for this suite.

## Receives

- `BeginValidator`

## Workflow

1. Receive `BeginValidator(pluginClone, replyTo)`.
2. **(Agentic Step)** Run the validation commands or workflows defined for this harness against `pluginClone` (read-only verification; do not start a new porting loop here).
    1. If every check passes, reply to `replyTo` with `ValidatorOutcome(passed = true, reports = <paths to validation logs or summaries>)` and end.
    2. If any check fails, reply to `replyTo` with `ValidatorOutcome(passed = false, reports = <paths to failure artifacts>)` and end.
