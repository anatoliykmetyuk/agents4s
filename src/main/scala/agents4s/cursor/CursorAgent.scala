package agents4s.cursor

import agents4s.Agent
import agents4s.tmux.{AgentConfig, Pane, TmuxAgent}

/** Drive a Cursor `agent` instance inside a detached tmux session. */
class CursorAgent(
    val workspace: os.Path,
    val model: String,
    val tmuxSocket: String = "cursor-agent",
    val label: String = "agent",
    val oneShot: Boolean = true,
    val config: AgentConfig = AgentConfig()
) extends TmuxAgent:

  override def agentBinaryName: String = "agent"

  override def buildCommand(binaryPath: String): Seq[String] =
    Seq(binaryPath, "--yolo", "--model", model, "--workspace", workspace.toString)

  override protected def promptStagingDir: os.Path =
    val d = workspace / ".cursor" / "prompts"
    os.makeDir.all(d)
    d

  def isTrustPrompt: Boolean =
    CursorTuiOps.isTrustPrompt(
      CursorTuiOps.tailText(requirePane, nLines = 20)(using config)
    )

  override def isReady: Boolean =
    CursorTuiOps.isReady(CursorTuiOps.tailText(requirePane, nLines = 20)(using config))

  override def isBusy: Boolean =
    CursorTuiOps.isBusy(CursorTuiOps.tailText(requirePane, nLines = 20)(using config))

  override def awaitReady(timeoutS: Double = Agent.DefaultTimeoutS): Unit =
    CursorTuiOps.awaitReady(requirePane, timeoutS)(using config)

  override def awaitBusy(timeoutS: Double = Agent.DefaultTimeoutS): Unit =
    CursorTuiOps.awaitBusy(requirePane, timeoutS)(using config)

  override def awaitDone(timeoutS: Double = Agent.DefaultTimeoutS): Unit =
    CursorTuiOps.awaitDone(requirePane, timeoutS)(using config)

end CursorAgent
