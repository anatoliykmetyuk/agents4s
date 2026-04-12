# The Gatekeeper Actor Specification

## Actor Purpose

Your purpose is to classify a plugin working tree before porting work: whether porting may proceed, the project is already on sbt 2, or blockers prevent porting.

## Suite references

- [01-messages.md](01-messages.md) — full message signatures and payloads for this suite.
- [06-definitions.md](06-definitions.md) — shared glossary for this suite.

## Receives

- `BeginGatekeeper`

## Workflow

1. Receive `BeginGatekeeper(pluginClone, replyTo)`.
2. **(Agentic Step)** Inspect and classify the tree at `pluginClone` for “already on sbt 2” vs “blocked by dependencies or policy” vs “OK to port”, using LLM judgment and tools where fixed rules are insufficient (harness prompt/resources for this actor).
    1. If the plugin is already fully ported and CI would be a no-op for the porting goal, reply to `replyTo` with `GatekeeperOutcome(cleared = false, alreadyPorted = true, blockedReasons = None)` and end.
    2. If show-stopping blockers exist, reply to `replyTo` with `GatekeeperOutcome(cleared = false, alreadyPorted = false, blockedReasons = Some(reasons))` using the same blocker component shapes as for `Blocked(reasons)` in the suite message catalog, and end.
    3. Otherwise reply to `replyTo` with `GatekeeperOutcome(cleared = true, alreadyPorted = false, blockedReasons = None)` and end.
