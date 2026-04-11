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

  private var _started: Boolean = false

  val startCommand: Seq[String] =
    Seq("agent", "--yolo", "--model", model, "--workspace", workspace.toString)

  override def start(): Unit =
    super.start()
    CursorTuiOps.awaitReady(pane)
    _started = true

  override def stop(): Unit =
    _started = false
    super.stop()

  override def isStarted: Boolean = _started

  def isTrustPrompt: Boolean =
    if pane == null then
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")
    CursorTuiOps.isTrustPrompt(CursorTuiOps.tailText(pane, nLines = 20))

  override def isBusy: Boolean =
    if pane == null then
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")
    CursorTuiOps.isBusy(CursorTuiOps.tailText(pane, nLines = 20))

end CursorAgent
