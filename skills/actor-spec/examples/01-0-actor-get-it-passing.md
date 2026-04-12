# Get It Passing Actor Specification

## Actor Purpose

Your purpose is to receive a raw Scala sbt plugin specification as an actor message, and ensure the corresponding plugin cross-compiles for sbt 2 with all the tests (as defined by the GitHub Actions CI workflows) passing. The request may identify the plugin by a GitHub URL (`java.net.URL`), a local clone (`java.nio.file.Path`), or both in a mutually exclusive way — exactly one of the two `Option` fields in `PortPluginRequest` must be defined.

## Definitions

- _Plugin Clones Directory_: root directory where this actor stores (or expects) local clones of plugins; layout is one subdirectory per plugin.
- _Plugin Directory_: directory name (single path segment) for this plugin’s clone inside _Plugin Clones Directory_.
- _Plugin Clones_: shorthand for the subtree `Plugin Clones Directory` / `Plugin Directory` — the working tree you operate on for the current plugin.
- _Default Branch_: the branch checked out for routine updates before porting (typically the repo’s mainline branch name from config or `git` default).
- _Port Branch_: the branch used for sbt 2 porting work; created if missing.

## Messaging Protocol

### Receives

- `PortPluginRequest(url: Option[java.net.URL], localPath: Option[java.nio.file.Path])` - Carries either a remote repository URL or a path to an existing clone; the actor clones or reuses the tree and drives porting to sbt 2. Exactly one of `url` or `localPath` must be `Some(...)`.

### Sends

- `AlreadyPorted` - The plugin is already ported to sbt 2; no further porting work is required.

- `Blocked(reasons: List[LibraryBlocker | PluginBlocker | OtherBlocker])` - Porting cannot proceed because of show-stopping blockers. `LibraryBlocker(org: String, name: String, notes: String)` and `PluginBlocker(org: String, name: String, notes: String)` capture dependency blockers; `OtherBlocker(notes: String)` covers remaining stop reasons.

- `PortingComplete(reports: List[java.nio.file.Path])` - Porting succeeded; `reports` lists markdown report files written during the run as filesystem paths.

- `PortingFailed(reports: List[java.nio.file.Path])` - Porting did not succeed; `reports` lists markdown report files captured during the attempt.

## Workflow

1. Receive the plugin port request.
2. Ensure the plugin is cloned to the Plugin Clones directory.
    1. First, check if the plugin is already cloned to the _Plugin Clones Directory_ by checking if the directory with the name _Plugin Directory_ exists in the _Plugin Clones Directory_.
    2. If it does, continue with the next step.
    3. If it doesn't, clone it to the _Plugin Clones_ directory under the _Plugin Directory_ directory.
3. Update the _Default Branch_.
    1. Check it out via `git checkout <_Default Branch_>`.
    2. Pull the latest changes from the upstream repository via `git pull`.
4. **(Agentic Step)** Spawn the Subagent [The Gatekeeper](01-1-actor-the-gatekeeper.md). It will reply either clearing the plugin for porting or denying the porting. If it has cleared the porting, proceed with the next steps. If it has denied porting, stop the workflow here and report to the requesting actor with a corresponding message according to your messaging protocol.
5. Check if the _Port Branch_ exists in the cloned repository. If yes, check it out. If not, create it by running `git checkout -b <_Port Branch_>`.
6. **(Agentic Step)** Spawn the Subagent [The Worker](01-2-actor-the-worker.md). It will attempt to port the plugin to sbt 2, ensuring the CI is passing. It may succeed or fail, and will reply with a corresponding message according to its messaging protocol. If it succeeds, proceed to the next step. If it fails, reply to the caller with the appropriate message and end the workflow here.
7. **(Agentic Step)** Spawn the Subagent [The Validator](01-3-actor-the-validator.md). It will validate the ported plugin to ensure it is indeed passing the CI. It may determine that the plugin is passing or failing the validation and will reply with a corresponding message according to its messaging protocol.
    1. In case the plugin is passing the validation, reply to the caller with the appropriate message and end the workflow here.
    2. In case the plugin is failing the validation, go back to step 6, spawning a new Worker Agent and sending it the same plugin along with the concerns reported by the validator. It is possible to return to step 6 no more than 3 times. If after 3 attempts the validator still determines that the plugin is failing the validation, reply to the caller with the appropriate message and end the workflow here.
