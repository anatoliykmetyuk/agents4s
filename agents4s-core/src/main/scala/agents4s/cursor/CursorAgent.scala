package agents4s.cursor

import agents4s.tmux.TmuxAgent

import java.nio.file.Path

/** Drive a Cursor `agent` instance inside a detached tmux session. */
class CursorAgent(
    val workspace: Path,
    val model: String,
    val socket: String = "cursor-agent",
    val label: String = "agent"
) extends TmuxAgent:

  val startCommand: Seq[String] =
    Seq("agent", "--yolo", "--model", model, "--workspace", workspace.toString)

  override def isStarted: Boolean = pane != null

  def isTrustPrompt: Boolean =
    if !isStarted then
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")
    CursorTuiOps.isTrustPrompt(CursorTuiOps.tailText(pane, nLines = 20))

  override def isIdle: Boolean =
    if !isStarted then false
    else
      val text = CursorTuiOps.tailText(pane, nLines = 20)
      if CursorTuiOps.isTrustPrompt(text) then
        pane.sendKeys("a", enter = false)
        false
      else CursorTuiOps.isReady(text)

  override def isBusy: Boolean =
    if !isStarted then
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")
    CursorTuiOps.isBusy(CursorTuiOps.tailText(pane, nLines = 20))

end CursorAgent
