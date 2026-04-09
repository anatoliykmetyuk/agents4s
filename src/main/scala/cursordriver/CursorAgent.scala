package cursordriver

import java.io.PrintStream
import java.nio.file.Files
import scala.collection.mutable
import java.util.concurrent.TimeoutException

/** Drive a Cursor `agent` instance inside a detached tmux session. */
class CursorAgent(
    val workspace: os.Path,
    val model: String,
    val tmuxSocket: String = "cursor-agent",
    val label: String = "agent",
    val quiet: Boolean = false,
    val killSession: Boolean = true,
    val tuiConfig: TuiConfig = TuiConfig(),
    whichExecutable: String => Option[String] = Paths.which,
    /** Sleep after sendKeys text before Enter (Python: 0.2s). */
    postSendKeysPause: Double => Unit = d => Thread.sleep(math.max(0L, (d * 1000.0).toLong)),
    newTmuxServer: String => TmuxServer = s => new TmuxServer(s),
    /** When false, [[stop]] skips tmux kill (for tests with mock panes). */
    killRemoteOnStop: Boolean = true,
    out: PrintStream = System.out,
    err: PrintStream = System.err
):

  var pane: Option[Pane] = None

  private[cursordriver] val promptPaths: mutable.ArrayBuffer[os.Path] =
    mutable.ArrayBuffer.empty[os.Path]

  private def promptStagingDir: os.Path =
    val d = workspace / ".cursor" / "prompts"
    os.makeDir.all(d)
    d

  private def discardPromptFile(path: os.Path): Unit =
    try os.remove(path, checkExists = false)
    catch case _: Exception => ()
    promptPaths.filterInPlace(_ != path)

  private def cleanupAllPromptFiles(): Unit =
    promptPaths.foreach(p =>
      try os.remove(p, checkExists = false)
      catch case _: Exception => ()
    )
    promptPaths.clear()

  private def requirePane: Pane =
    pane.getOrElse:
      throw new RuntimeException("CursorAgent is not started; call start() first")

  def stop(): Unit =
    if killRemoteOnStop then
      try
        val tmux = newTmuxServer(tmuxSocket)
        tmux.killSession(label)
      catch case _: Exception => ()
    cleanupAllPromptFiles()
    pane = None

  def isTrustPrompt: Boolean =
    TuiOps.isTrustPrompt(TuiOps.tailText(requirePane, nLines = 20)(using tuiConfig))

  def isReady: Boolean =
    TuiOps.isReady(TuiOps.tailText(requirePane, nLines = 20)(using tuiConfig))

  def isBusy: Boolean =
    TuiOps.isBusy(TuiOps.tailText(requirePane, nLines = 20)(using tuiConfig))

  def awaitReady(timeoutS: Double = TuiOps.AgentTimeoutS): Unit =
    TuiOps.awaitReady(requirePane, timeoutS)(using tuiConfig)

  def awaitBusy(timeoutS: Double = TuiOps.AgentTimeoutS): Unit =
    TuiOps.awaitBusy(requirePane, timeoutS)(using tuiConfig)

  def awaitDone(timeoutS: Double = TuiOps.AgentTimeoutS): Unit =
    TuiOps.awaitDone(requirePane, timeoutS)(using tuiConfig)

  def sendPrompt(
      text: String,
      timeoutS: Double = TuiOps.AgentTimeoutS,
      promptAsFile: Boolean = true
  ): Unit =
    val p = requirePane
    awaitReady(timeoutS)
    val textToSend =
      if promptAsFile then
        os.makeDir.all(promptStagingDir)
        val nio = Files.createTempFile(
          promptStagingDir.toNIO,
          "cursor-driver-prompt-",
          ".md"
        )
        val path = os.Path(nio)
        os.write.over(path, text)
        promptPaths += path
        s"Read and follow the instructions in $path"
      else text
    p.sendKeys(textToSend, enter = false)
    postSendKeysPause(0.2)
    p.sendKeys("", enter = true)
    awaitBusy()

  /** @return 0 success, 127 missing agent binary, 1 error/timeout */
  def start(prompt: Option[String] = None): Int =
    whichExecutable("agent") match
      case None =>
        err.println("error: `agent` not found on PATH (install Cursor CLI)")
        pane = None
        127
      case Some(agentBin) =>
        val tmux = newTmuxServer(tmuxSocket)

        cleanupAllPromptFiles()
        pane = None
        var promptPathOpt: Option[os.Path] = None
        try
          prompt.foreach { pr =>
            os.makeDir.all(promptStagingDir)
            val nio = Files.createTempFile(
              promptStagingDir.toNIO,
              "cursor-driver-prompt-",
              ".md"
            )
            val path = os.Path(nio)
            os.write.over(path, pr)
            promptPaths += path
            promptPathOpt = Some(path)
          }

          if !quiet then
            out.println(s"[$label] starting agent in tmux ...")
            out.println(s"[$label] attach with:  tmux -L $tmuxSocket attach -t $label")

          val cmd = Seq(agentBin, "--yolo", "--model", model, "--workspace", workspace.toString)
          val target = tmux.newSession(label, workspace, cmd)
          pane = Some(new TmuxPane(tmuxSocket, target))

          prompt match
            case None => 0
            case Some(_) =>
              val pathStr = promptPathOpt.get.toString
              val shortPrompt = s"Read and follow the instructions in $pathStr"
              sendPrompt(shortPrompt, promptAsFile = false)
              awaitDone()
              if !quiet then out.println(s"[$label] done.")
              0
        catch
          case e: TimeoutException =>
            err.println(s"[$label] timeout: ${e.getMessage}")
            1
          case e: Exception =>
            err.println(s"[$label] error: ${e.getMessage}")
            1
        finally
          promptPathOpt.foreach(discardPromptFile)
          if killSession then stop()
        end try
    end match
  end start

end CursorAgent
