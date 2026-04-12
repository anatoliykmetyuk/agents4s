# Get It Passing Actor Specification

## Actor Purpose

Your purpose is to take ownership of porting a Scala sbt plugin to sbt 2 so it cross-compiles and the project’s CI checks pass.

## Suite references

- [01-messages.md](01-messages.md) — full message signatures and payloads for this suite.
- [06-definitions.md](06-definitions.md) — glossary for every `_Term_` used in the Workflow below.

## Receives

- `PortPluginRequest`
- `GatekeeperOutcome`
- `WorkerOutcome`
- `ValidatorOutcome`

## Workflow

1. Receive the plugin port request.
2. Ensure the plugin is cloned to the Plugin Clones directory.
    1. First, check if the plugin is already cloned to the _Plugin Clones Directory_ by checking if the directory with the name _Plugin Directory_ exists in the _Plugin Clones Directory_.
    2. If it does, continue with the next step.
    3. If it doesn't, clone it to the _Plugin Clones_ directory under the _Plugin Directory_ directory.
3. Update the _Default Branch_.
    1. Check it out via `git checkout <_Default Branch_>`.
    2. Pull the latest changes from the upstream repository via `git pull`.
4. Spawn the Subagent [The Gatekeeper](03-actor-the-gatekeeper.md). It will reply either clearing the plugin for porting, reporting already ported, or denying the porting. If it has cleared the porting, proceed with the next steps. If the plugin is already ported, reply to `replyTo` with `AlreadyPorted` and end. If it has denied porting, reply to `replyTo` with `Blocked(reasons)` (using the blocker list from the gatekeeper outcome) and end.
5. Check if the _Port Branch_ exists in the cloned repository. If yes, check it out. If not, create it by running `git checkout -b <_Port Branch_>`.
6. Spawn the Subagent [The Worker](04-actor-the-worker.md). It will attempt to port the plugin to sbt 2, ensuring the CI is passing. It may succeed or fail. If it succeeds, proceed to the next step. If it fails, reply to `replyTo` with `PortingFailed(reports)` and end the workflow here.
7. Spawn the Subagent [The Validator](05-actor-the-validator.md). It will validate the ported plugin. If validation passes, reply to `replyTo` with `PortingComplete(reports)` and end. If validation fails, go back to step 6 (bounded retry: at most 3 returns to step 6). If still failing after that, reply to `replyTo` with `PortingFailed(reports)`.
