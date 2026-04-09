package cursordriver

import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

/** TUI markers, pane tail, and Cursor agent lifecycle helpers. */
object TuiOps:

  val FooterMarker: String = "Auto-run"
  val BusyMarker: String = "ctrl+c to stop"
  val TrustMarker: String = "Trust this workspace"

  val PollIntervalS: Double = 1.0
  val AgentTimeoutS: Double = 30 * 60

  private val ansiRe: Pattern = Pattern.compile("\u001b\\[[\\d;]*[A-Za-z]")

  def stripAnsi(text: String): String =
    ansiRe.matcher(text).replaceAll("")

  def tailText(pane: Pane, nLines: Int = 10)(using TuiConfig): String =
    val lines = pane.capturePane(start = -nLines)
    stripAnsi(lines.mkString("\n"))

  def isTrustPrompt(text: String): Boolean =
    text.contains(TrustMarker) && !text.contains(FooterMarker)

  def isReady(text: String): Boolean =
    text.contains(FooterMarker) && !text.contains(BusyMarker)

  def isBusy(text: String): Boolean =
    text.contains(BusyMarker)

  def handleTrust(pane: Pane, timeoutS: Double = AgentTimeoutS)(using cfg: TuiConfig): Unit =
    pane.sendKeys("a", enter = false)
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      if !isTrustPrompt(tailText(pane, nLines = 20)) then return
      cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException("trust dialog did not dismiss")

  def awaitReady(pane: Pane, timeoutS: Double = AgentTimeoutS)(using cfg: TuiConfig): Unit =
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      val text = tailText(pane, nLines = 20)
      if isReady(text) then return
      if isTrustPrompt(text) then
        val remaining = ((deadline - cfg.clockNanos()).max(0L)) / 1e9
        handleTrust(pane, remaining)
      else cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException("agent did not become ready in time")

  def awaitBusy(pane: Pane, timeoutS: Double = AgentTimeoutS)(using cfg: TuiConfig): Unit =
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      if isBusy(tailText(pane, nLines = 20)) then return
      cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException("agent never started working")

  def awaitDone(pane: Pane, timeoutS: Double = AgentTimeoutS)(using cfg: TuiConfig): Unit =
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      if !isBusy(tailText(pane, nLines = 20)) then return
      cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException(s"agent work exceeded ${timeoutS}s")

end TuiOps
