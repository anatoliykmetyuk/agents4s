package agents4s.cursor

import agents4s.tmux.{Pane, TmuxServer}

import java.util.concurrent.TimeoutException

/** Cursor `agent` CLI TUI markers and lifecycle helpers. */
object CursorTuiOps:

  /** Default maximum wait for readiness / work completion (seconds). */
  val DefaultTimeoutS: Double = 30 * 60

  val FooterMarker: String = "Auto-run"
  val BusyMarker: String = "ctrl+c to stop"
  val TrustMarker: String = "Trust this workspace"

  val PollIntervalS: Double = 1.0

  def tailText(pane: Pane, nLines: Int = 10): String =
    val lines = pane.capturePane(start = -nLines)
    TmuxServer.stripAnsi(lines.mkString("\n"))

  def isTrustPrompt(text: String): Boolean =
    text.contains(TrustMarker) && !text.contains(FooterMarker)

  def isReady(text: String): Boolean =
    text.contains(FooterMarker) && !text.contains(BusyMarker)

  def isBusy(text: String): Boolean =
    text.contains(BusyMarker)

  def handleTrust(
      pane: Pane,
      timeoutS: Double = DefaultTimeoutS,
      pollIntervalMs: Long = 1000
  ): Unit =
    pane.sendKeys("a", enter = false)
    val deadline = System.nanoTime() + (timeoutS * 1e9).toLong
    while System.nanoTime() < deadline do
      if !isTrustPrompt(tailText(pane, nLines = 20)) then return
      Thread.sleep(math.max(0L, pollIntervalMs))
    throw new TimeoutException("trust dialog did not dismiss")

  def awaitReady(
      pane: Pane,
      timeoutS: Double = DefaultTimeoutS,
      pollIntervalMs: Long = 1000
  ): Unit =
    val deadline = System.nanoTime() + (timeoutS * 1e9).toLong
    while System.nanoTime() < deadline do
      val text = tailText(pane, nLines = 20)
      if isReady(text) then return
      if isTrustPrompt(text) then
        val remaining = ((deadline - System.nanoTime()).max(0L)) / 1e9
        handleTrust(pane, remaining, pollIntervalMs)
      else Thread.sleep(math.max(0L, pollIntervalMs))
    throw new TimeoutException("agent did not become ready in time")

  def awaitBusy(pane: Pane, timeoutS: Double = DefaultTimeoutS, pollIntervalMs: Long = 1000): Unit =
    val deadline = System.nanoTime() + (timeoutS * 1e9).toLong
    while System.nanoTime() < deadline do
      if isBusy(tailText(pane, nLines = 20)) then return
      Thread.sleep(math.max(0L, pollIntervalMs))
    throw new TimeoutException("agent never started working")

  def awaitDone(pane: Pane, timeoutS: Double = DefaultTimeoutS, pollIntervalMs: Long = 1000): Unit =
    val deadline = System.nanoTime() + (timeoutS * 1e9).toLong
    while System.nanoTime() < deadline do
      if !isBusy(tailText(pane, nLines = 20)) then return
      Thread.sleep(math.max(0L, pollIntervalMs))
    throw new TimeoutException(s"agent work exceeded ${timeoutS}s")

end CursorTuiOps
