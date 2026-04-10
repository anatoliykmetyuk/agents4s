package agents4s.cursor

import agents4s.Agent
import agents4s.tmux.{AgentConfig, Pane, TmuxServer}

import java.util.concurrent.TimeoutException

/** Cursor `agent` CLI TUI markers and lifecycle helpers. */
object CursorTuiOps:

  val FooterMarker: String = "Auto-run"
  val BusyMarker: String = "ctrl+c to stop"
  val TrustMarker: String = "Trust this workspace"

  val PollIntervalS: Double = 1.0

  def tailText(pane: Pane, nLines: Int = 10)(using cfg: AgentConfig): String =
    val lines = pane.capturePane(start = -nLines)
    TmuxServer.stripAnsi(lines.mkString("\n"))

  def isTrustPrompt(text: String): Boolean =
    text.contains(TrustMarker) && !text.contains(FooterMarker)

  def isReady(text: String): Boolean =
    text.contains(FooterMarker) && !text.contains(BusyMarker)

  def isBusy(text: String): Boolean =
    text.contains(BusyMarker)

  def handleTrust(pane: Pane, timeoutS: Double = Agent.DefaultTimeoutS)(using
      cfg: AgentConfig
  ): Unit =
    pane.sendKeys("a", enter = false)
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      if !isTrustPrompt(tailText(pane, nLines = 20)) then return
      cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException("trust dialog did not dismiss")

  def awaitReady(pane: Pane, timeoutS: Double = Agent.DefaultTimeoutS)(using
      cfg: AgentConfig
  ): Unit =
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      val text = tailText(pane, nLines = 20)
      if isReady(text) then return
      if isTrustPrompt(text) then
        val remaining = ((deadline - cfg.clockNanos()).max(0L)) / 1e9
        handleTrust(pane, remaining)
      else cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException("agent did not become ready in time")

  def awaitBusy(pane: Pane, timeoutS: Double = Agent.DefaultTimeoutS)(using
      cfg: AgentConfig
  ): Unit =
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      if isBusy(tailText(pane, nLines = 20)) then return
      cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException("agent never started working")

  def awaitDone(pane: Pane, timeoutS: Double = Agent.DefaultTimeoutS)(using
      cfg: AgentConfig
  ): Unit =
    val deadline = cfg.clockNanos() + (timeoutS * 1e9).toLong
    while cfg.clockNanos() < deadline do
      if !isBusy(tailText(pane, nLines = 20)) then return
      cfg.sleeper(cfg.pollIntervalS)
    throw new TimeoutException(s"agent work exceeded ${timeoutS}s")

end CursorTuiOps
