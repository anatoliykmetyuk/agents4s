# Messages

All inter-actor messages for the example harness. Pseudocode only; the actor-harness skill maps these to Scala types in `messages.scala`.

- `PortPluginRequest(url: Option[java.net.URL], localPath: Option[java.nio.file.Path], replyTo: ActorRef[_PortPluginOutcome_])` - Carries either a remote repository URL or a path to an existing clone; the actor clones or reuses the tree and drives porting to sbt 2. Exactly one of `url` or `localPath` must be `Some(...)`.

- `BeginGatekeeper(pluginClone: java.nio.file.Path, replyTo: ActorRef[GatekeeperOutcome])` - Parent asks The Gatekeeper to classify the plugin tree before porting work begins.

- `BeginWorker(pluginClone: java.nio.file.Path, portBranch: String, replyTo: ActorRef[WorkerOutcome])` - Parent asks The Worker to port the plugin on the named branch until CI passes or the attempt fails.

- `BeginValidator(pluginClone: java.nio.file.Path, replyTo: ActorRef[ValidatorOutcome])` - Parent asks The Validator to verify the ported plugin (tests / CI as defined for this harness).

- `AlreadyPorted` - The plugin is already ported to sbt 2; no further porting work is required.

- `Blocked(reasons: List[LibraryBlocker | PluginBlocker | OtherBlocker])` - Porting cannot proceed because of show-stopping blockers. `LibraryBlocker(org: String, name: String, notes: String)` and `PluginBlocker(org: String, name: String, notes: String)` capture dependency blockers; `OtherBlocker(notes: String)` covers remaining stop reasons.

- `PortingComplete(reports: List[java.nio.file.Path])` - Porting succeeded; `reports` lists markdown report files written during the run as filesystem paths.

- `PortingFailed(reports: List[java.nio.file.Path])` - Porting did not succeed; `reports` lists markdown report files captured during the attempt.

- `GatekeeperOutcome(cleared: Boolean, alreadyPorted: Boolean, blockedReasons: Option[List[LibraryBlocker | PluginBlocker | OtherBlocker]])` - The Gatekeeper’s reply to the parent; exactly one branch applies: `cleared`, `alreadyPorted`, or `blockedReasons.isDefined` (see [03-actor-the-gatekeeper.md](03-actor-the-gatekeeper.md); glossary in [06-definitions.md](06-definitions.md)).

- `WorkerOutcome(success: Boolean, reports: List[java.nio.file.Path])` - The Worker’s reply after a porting attempt; on `success == false`, the parent ends with `PortingFailed(reports)`.

- `ValidatorOutcome(passed: Boolean, reports: List[java.nio.file.Path])` - The Validator’s reply; when `passed == false`, the parent retries The Worker (bounded) or ends with `PortingFailed(reports)`.
