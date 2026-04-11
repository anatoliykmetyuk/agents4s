package agents4s

import os.Path

/** Universal agent interface (tmux-based or otherwise).
  *
  * Typical flow: `start`, then `sendPrompt` as needed; poll `isReady` / `isBusy` or use
  * `awaitReady` / `awaitBusy` / `awaitDone`; finish with `stop`.
  */
trait Agent:

  /** Filesystem root this agent uses for reads, writes, and session state. */
  def workspace: Path

  /** Model identifier passed to the underlying runtime (CLI or API), e.g. provider-specific name.
    */
  def model: String

  /** Spawns or attaches to the agent session; optional `prompt` seeds the first turn.
    *
    * @return
    *   0 success, 127 missing binary, 1 error/timeout
    */
  def start(prompt: Option[String] = None): Int

  /** Tears down the session; retries interrupts up to `interruptAttempts` before giving up. */
  def stop(interruptAttempts: Int = 10): Unit

  /** Submits user input: waits for ready (up to `timeoutS`), sends keys, then waits until busy
    * (separate default timeout).
    *
    * When `promptAsFile` is true, text is written to a staged file and the session gets a short
    * path reference instead of pasting the body.
    */
  def sendPrompt(
      text: String,
      timeoutS: Double = Agent.DefaultTimeoutS,
      promptAsFile: Boolean = true
  ): Unit

  /** True when the agent accepts a new prompt (idle / at a prompt), not mid-generation. */
  def isReady: Boolean

  /** True while the agent is producing output or otherwise not at a stable prompt. */
  def isBusy: Boolean

  /** Blocks until `isReady` becomes true or `timeoutS` elapses (implementation may throw on
    * timeout).
    */
  def awaitReady(timeoutS: Double = Agent.DefaultTimeoutS): Unit

  /** Blocks until `isBusy` becomes true (work has started) or `timeoutS` elapses. */
  def awaitBusy(timeoutS: Double = Agent.DefaultTimeoutS): Unit

  /** Blocks until `isBusy` is false (run paused or finished) or `timeoutS` elapses; typically
    * throws on timeout.
    */
  def awaitDone(timeoutS: Double = Agent.DefaultTimeoutS): Unit

end Agent

object Agent:

  /** Default maximum wait for readiness / work completion (seconds). */
  val DefaultTimeoutS: Double = 30 * 60

end Agent
