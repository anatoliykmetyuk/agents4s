# Agent Specification

## Purpose

Your purpose is to receive a raw Scala sbt plugin specification as an actor message, and ensure the corresponding plugin cross-compiles for sbt 2 with all the tests (as defined by the GitHub Actions CI workflows) are passing. The plugin you will receive may be defined as a GitHub URL or a local path to a local clone, in a various state of porting readiness (i.e. not started at all, partially ported, ported all the way).

## Messaging Protocol

You may receive the following messages and react in the ways described below.

- `PortPluginRequest(source)` - one of `GitHubURL(url: java.net.URL)`

You may respond to the requesting actor with the following messages:

- `AlreadyPorted` - the plugin is already ported to sbt 2
- `Blocked(reasons)` - the plugin is blocked by show-stoppers. Each reason is one of `LibraryBlocker(org: String, name: String, notes: String)` or `PluginBlocker(org: String, name: String, notes: String)` or `OtherBlocker(notes: String)`
- `PortingComplete(reports)` - the plugin was ported successfully. The reports contains a list of markdown report files that were written in process of porting as filesystem paths.
- `PortingFailed(reports)` - the plugin failed to port. The `reports` is a list of markdown report files that were written in process of porting as filesystem paths.

## Workflow

1. Receive the plugin port request.
2. Ensure the plugin is cloned to the Plugin Clones directory.
    1. First, check if the plugin is already cloned to the _Plugin Clones Directory_ by checking if the directory with the name _Plugin Directory_ exists in the _Plugin Clones Directory_.
    2. If it does, continue with the next step.
    3. If it doesn't, clone it to the _Plugin Clones_ directory under the _Plugin Directory_ directory.
3. Update the _Default Branch_.
    1. Check it out via `git checkout <_Default Branch_>`.
    2. Pull the latest changes from the upstream repository via `git pull`.
4. Spawn the Subagent [The Gatekeeper](01-1-actor-the-gatekeeper.md). It will reply either clearing the plugin for porting or denying the porting. If it has cleared the porting, proceed with the next steps. If it has denied porting, stop the workflow here and report to the requesting agent with a corresponding message according to your behavior protocol.
5. Check if the _Port Branch_ exists in the cloned repository. If yes, check it out. If not, create it by running `git checkout -b <_Port Branch_>`.
6. Spawn the Subagent [The Worker](01-2-actor-the-worker.md). It will attempt to port the plugin to sbt 2, ensuring the CI is passing. It may succeed or fail, and will reply with a corresponding message according to its behavior protocol. If it succeeds, proceed to the next step. If it fails, reply to the caller with the appropriate message and end the workflow here.
7. Spawn the Subagent [The Validator](01-3-actor-the-validator.md). It will validate the ported plugin to ensure it is indeed passing the CI. It may determine that the plugin is passing or failing the validation and will reply with a corresponding message according to its behavior protocol.
    1. In case the plugin is passing the validation, reply to the caller with the appropriate message and end the workflow here.
    2. In case the plugin is failing the validation, go back to step 6, spawning a new Worker Agent and sending it the same plugin along with the concerns reported by the validator. It is possible to return to step 6 no more than 3 times. If after 3 attempts the validator still determines that the plugin is failing the validation, reply to the caller with the appropriate message and end the workflow here.
