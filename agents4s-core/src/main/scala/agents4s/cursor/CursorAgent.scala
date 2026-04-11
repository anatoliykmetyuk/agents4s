package agents4s.cursor

import agents4s.tmux.{AgentConfig, Pane, TmuxAgent}

import scala.concurrent.duration.Duration

/** Drive a Cursor `agent` instance inside a detached tmux session. */
class CursorAgent(
    val workspacePath: os.Path,
    val model: String,
    val tmuxSocket: String = "cursor-agent",
    val label: String = "agent",
    val oneShot: Boolean = true,
    val config: AgentConfig = AgentConfig()
) extends TmuxAgent:

  override def agentBinaryName: String = "agent"

  override def buildCommand(binaryPath: String): Seq[String] =
    Seq(binaryPath, "--yolo", "--model", model, "--workspace", workspacePath.toString)

  override protected def promptStagingDir: os.Path =
    val d = workspacePath / ".cursor" / "prompts"
    os.makeDir.all(d)
    d

  def isTrustPrompt: Boolean =
    CursorTuiOps.isTrustPrompt(
      CursorTuiOps.tailText(requirePane, nLines = 20)(using config)
    )

  override def isIdle: Boolean =
    pane.exists: _ =>
      CursorTuiOps.isReady(CursorTuiOps.tailText(requirePane, nLines = 20)(using config))

  override def isBusy: Boolean =
    CursorTuiOps.isBusy(CursorTuiOps.tailText(requirePane, nLines = 20)(using config))

  override def awaitBusy(timeout: Duration): Unit =
    CursorTuiOps.awaitBusy(
      requirePane,
      timeout.toMillis / 1000.0
    )(using config)

  override def awaitIdle(timeout: Duration): Unit =
    CursorTuiOps.awaitReady(
      requirePane,
      timeout.toMillis / 1000.0
    )(using config)

end CursorAgent
