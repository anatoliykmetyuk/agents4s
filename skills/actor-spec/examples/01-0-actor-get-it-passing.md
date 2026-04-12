# Get It Passing Actor Specification

## Actor Purpose

Your purpose is to receive a raw Scala sbt plugin specification as an actor message, and ensure the corresponding plugin cross-compiles for sbt 2 with all the tests (as defined by the GitHub Actions CI workflows) passing. The request may identify the plugin by a GitHub URL (`java.net.URL`), a local clone (`java.nio.file.Path`), or both in a mutually exclusive way — exactly one of the two `Option` fields in `PortPluginRequest` must be defined (see [messages.md](messages.md)). Outcome messages you send back (`AlreadyPorted`, `Blocked`, `PortingComplete`, `PortingFailed`) are **Receives** of [Port Plugin Client](01-4-actor-port-plugin-client.md); reply using the message names from [messages.md](messages.md) in the Workflow.

## Definitions

- _PortPluginOutcome_: the union of outcome messages in [messages.md](messages.md) (`AlreadyPorted`, `Blocked`, `PortingComplete`, `PortingFailed`); use as the `replyTo` payload type on `PortPluginRequest`.
- _Plugin Clones Directory_: root directory where this actor stores (or expects) local clones of plugins; layout is one subdirectory per plugin.
- _Plugin Directory_: directory name (single path segment) for this plugin’s clone inside _Plugin Clones Directory_.
- _Plugin Clones_: shorthand for the subtree `Plugin Clones Directory` / `Plugin Directory` — the working tree you operate on for the current plugin.
- _Default Branch_: the branch checked out for routine updates before porting (typically the repo’s mainline branch name from config or `git` default).
- _Port Branch_: the branch used for sbt 2 porting work; created if missing.

## Messaging Protocol

### Receives

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
4. **(Agentic Step)** Spawn the Subagent [The Gatekeeper](01-1-actor-the-gatekeeper.md). It will reply either clearing the plugin for porting, reporting already ported, or denying the porting. If it has cleared the porting, proceed with the next steps. If the plugin is already ported, reply with `AlreadyPorted` and end. If it has denied porting, reply with `Blocked(reasons)` (using the blocker list from the gatekeeper outcome) and end.
5. Check if the _Port Branch_ exists in the cloned repository. If yes, check it out. If not, create it by running `git checkout -b <_Port Branch_>`.
6. **(Agentic Step)** Spawn the Subagent [The Worker](01-2-actor-the-worker.md). It will attempt to port the plugin to sbt 2, ensuring the CI is passing. It may succeed or fail. If it succeeds, proceed to the next step. If it fails, reply with `PortingFailed(reports)` and end the workflow here.
7. **(Agentic Step)** Spawn the Subagent [The Validator](01-3-actor-the-validator.md). It will validate the ported plugin. If validation passes, reply with `PortingComplete(reports)` and end. If validation fails, go back to step 6 (bounded retry: at most 3 returns to step 6). If still failing after that, reply with `PortingFailed(reports)`.
