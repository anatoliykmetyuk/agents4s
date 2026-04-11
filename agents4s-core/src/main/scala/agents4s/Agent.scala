package agents4s

import scala.concurrent.duration.*
import java.util.concurrent.TimeoutException

/** Universal agent interface (tmux-based or otherwise).
  *
  * Typical flow: `start`, then `sendPrompt` as needed; poll `isIdle` / `isBusy` or use
  * `awaitStarted` / `awaitBusy` / `awaitIdle`; finish with `stop`.
  */
trait Agent:

  /** Filesystem root this agent uses for reads, writes, and session state.
    */
  def workspace: java.nio.file.Path

  /** Model identifier passed to the underlying runtime (CLI or API), e.g. provider-specific name.
    */
  def model: String

  /** Spawns or attaches to the agent session
    *
    * @throws RuntimeException
    *   if the agent binary is missing
    */
  def start(): Unit

  /** Tears down the session.
    */
  def stop(): Unit

  /** Submits user prompt to the agent. This method is non-blocking and returns immediately.
    *
    * @throws RuntimeException
    *   if the agent is busy or not started
    * @param prompt
    *   the prompt to send to the agent
    * @param promptAsFile
    *   if true, the prompt is written to a staged file and the session gets a short path reference
    *   instead of pasting the body
    */
  def sendPrompt(prompt: String, promptAsFile: Boolean): Unit

  /** True when the agent has started. An agent in Started state may be either idle (ready to accept
    * new prompts) or busy (generating output from a previous prompt).
    */
  def isStarted: Boolean

  /** True when the agent is busy. An Agent is Busy when it is generating output from a previous
    * prompt.
    */
  def isBusy: Boolean

  /** True when the agent is idle. An Agent is Idle when it is ready to accept new prompts. An Agent
    * is assumed to be ready to accept new prompts when it is started and not busy.
    */
  def isIdle: Boolean = isStarted && !isBusy

  def awaitStarted(
      timeout: Duration,
      pollInterval: Duration = 1.second
  ): Unit = await(_ => isStarted, timeout, pollInterval)

  def awaitBusy(
      timeout: Duration,
      pollInterval: Duration = 1.second
  ): Unit = await(_ => isBusy, timeout, pollInterval)

  def awaitIdle(
      timeout: Duration,
      pollInterval: Duration = 1.second
  ): Unit = await(_ => isIdle, timeout, pollInterval)

  /** Blocks until the predicate is true or the timeout elapses.
    *
    * @param predicate
    *   the predicate to wait for
    * @param timeout
    *   maximum time to wait for the predicate to become true
    * @param pollInterval
    *   delay between predicate checks
    * @throws TimeoutException
    *   if the predicate does not become true within the timeout
    */
  def await(
      predicate: Agent => Boolean,
      timeout: Duration,
      pollInterval: Duration = 1.second
  ): Unit =
    val deadline = System.nanoTime() + timeout.toNanos.toLong
    val sleepMs = pollInterval.toMillis.max(1L)
    while !predicate(this) && System.nanoTime() < deadline do Thread.sleep(sleepMs)
    if !predicate(this) then
      throw TimeoutException(s"predicate did not become true within ${timeout.toSeconds} seconds")
