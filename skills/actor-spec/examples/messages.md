# Messages

All inter-actor messages for the example harness. Pseudocode only; the actor-harness skill maps these to Scala types in `messages.scala`.

- `PortPluginRequest(url: Option[java.net.URL], localPath: Option[java.nio.file.Path], replyTo: ActorRef[_PortPluginOutcome_])` - Carries either a remote repository URL or a path to an existing clone; the actor clones or reuses the tree and drives porting to sbt 2. Exactly one of `url` or `localPath` must be `Some(...)`.

- `AlreadyPorted` - The plugin is already ported to sbt 2; no further porting work is required.

- `Blocked(reasons: List[LibraryBlocker | PluginBlocker | OtherBlocker])` - Porting cannot proceed because of show-stopping blockers. `LibraryBlocker(org: String, name: String, notes: String)` and `PluginBlocker(org: String, name: String, notes: String)` capture dependency blockers; `OtherBlocker(notes: String)` covers remaining stop reasons.

- `PortingComplete(reports: List[java.nio.file.Path])` - Porting succeeded; `reports` lists markdown report files written during the run as filesystem paths.

- `PortingFailed(reports: List[java.nio.file.Path])` - Porting did not succeed; `reports` lists markdown report files captured during the attempt.

- `GatekeeperOutcome` - Structured replies from The Gatekeeper after you spawn it (cleared / already ported / blocked). Exact variants are shared with the gatekeeper spec and generated once in `messages.scala`.

- `WorkerOutcome` - Replies from The Worker after porting attempts.

- `ValidatorOutcome` - Replies from The Validator after validation.
