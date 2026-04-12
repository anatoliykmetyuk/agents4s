# Definitions

Shared glossary for the example suite. Every `_Term_` used in [02-actor-get-it-passing.md](02-actor-get-it-passing.md) appears here; other entries document names used in [01-messages.md](01-messages.md) or child specs.

- _PortPluginOutcome_: the union of client-facing outcome messages in [01-messages.md](01-messages.md) (`AlreadyPorted`, `Blocked`, `PortingComplete`, `PortingFailed`); the `replyTo` payload type on `PortPluginRequest`.
- _Gatekeeper branch_: the outcome encoded in `GatekeeperOutcome` — exactly one of cleared for porting, already ported, or blocked with reasons (full shape in [01-messages.md](01-messages.md); behavior in [03-actor-the-gatekeeper.md](03-actor-the-gatekeeper.md)).
- _Plugin Clones Directory_: root directory where this actor stores (or expects) local clones of plugins; layout is one subdirectory per plugin.
- _Plugin Directory_: directory name (single path segment) for this plugin’s clone inside _Plugin Clones Directory_.
- _Plugin Clones_: shorthand for the subtree _Plugin Clones Directory_ / _Plugin Directory_ — the working tree you operate on for the current plugin.
- _Default Branch_: the branch checked out for routine updates before porting (typically the repo’s mainline branch name from config or `git` default).
- _Port Branch_: the branch used for sbt 2 porting work; created if missing.
- _Port branch_: the git branch name passed in `BeginWorker` where porting edits are applied (the parent’s _Port Branch_).
