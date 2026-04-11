package agents4s

import os.Path

/** Universal agent interface (tmux-based or otherwise). */
trait Agent:

  def workspace: Path
  def model: String

  /** @return 0 success, 127 missing binary, 1 error/timeout */
  def start(prompt: Option[String] = None): Int

  def stop(interruptAttempts: Int = 10): Unit

  def sendPrompt(
      text: String,
      timeoutS: Double = Agent.DefaultTimeoutS,
      promptAsFile: Boolean = true
  ): Unit

  def isReady: Boolean
  def isBusy: Boolean

  def awaitReady(timeoutS: Double = Agent.DefaultTimeoutS): Unit
  def awaitBusy(timeoutS: Double = Agent.DefaultTimeoutS): Unit
  def awaitDone(timeoutS: Double = Agent.DefaultTimeoutS): Unit

end Agent

object Agent:

  /** Default maximum wait for readiness / work completion (seconds). */
  val DefaultTimeoutS: Double = 30 * 60

end Agent
