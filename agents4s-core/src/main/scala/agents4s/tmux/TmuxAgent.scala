package agents4s.tmux

import agents4s.Agent

import java.nio.file.Files
import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

/** Shared tmux session lifecycle; subclasses supply binary, command line, and TUI semantics. */
trait TmuxAgent extends Agent:

  /** Internal workspace root as [[os.Path]] (tmux / os-lib operations). */
  val workspacePath: os.Path
  val model: String
  val tmuxSocket: String
  val label: String

  /** When true, [[start]] calls [[stop]] in `finally` (one-shot run). */
  val oneShot: Boolean
  val config: AgentConfig

  override def workspace: java.nio.file.Path = workspacePath.toNIO

  var pane: Option[Pane] = None

  private[agents4s] val promptPaths: mutable.ArrayBuffer[os.Path] =
    mutable.ArrayBuffer.empty[os.Path]

  protected def promptStagingDir: os.Path

  /** Executable name resolved by [[AgentConfig.whichExecutable]] (e.g. `"agent"`). */
  def agentBinaryName: String

  def buildCommand(binaryPath: String): Seq[String]

  def isBusy: Boolean

  override def isStarted: Boolean = pane.isDefined

  private val defaultRunTimeout = (30 * 60).seconds

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

  protected def requirePane: Pane =
    pane.getOrElse:
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")

  override def stop(): Unit =
    val interruptAttempts = 10
    pane match
      case None => ()
      case Some(_) =>
        try if isBusy then interruptUntilIdle(interruptAttempts)
        catch case _: Exception => ()

        if config.killRemoteOnStop then
          try
            val tmux = config.newTmuxServer(tmuxSocket)
            tmux.killSession(label)
          catch case _: Exception => ()
        cleanupAllPromptFiles()
        pane = None
    end match
  end stop

  private def interruptUntilIdle(maxAttempts: Int): Unit =
    val p = requirePane
    var remaining = maxAttempts
    while remaining > 0 do
      try
        p.sendInterrupt()
        config.sleeper(config.pollIntervalS)
        if !isBusy then return
      catch case _: Exception => return
      remaining -= 1

  /** Send prompt keys without blocking on ready/busy (for actor heartbeat polling). */
  def firePrompt(text: String, promptAsFile: Boolean = true): Unit =
    val p = requirePane
    val textToSend =
      if promptAsFile then
        os.makeDir.all(promptStagingDir)
        val nio = Files.createTempFile(
          promptStagingDir.toNIO,
          "agents4s-prompt-",
          ".md"
        )
        val path = os.Path(nio)
        os.write.over(path, text)
        promptPaths += path
        s"Read and follow the instructions in $path"
      else text
    p.sendKeys(textToSend, enter = false)
    config.postSendKeysPause(0.2)
    p.sendKeys("", enter = true)

  override def sendPrompt(text: String, promptAsFile: Boolean): Unit =
    if !isStarted then
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")
    if isBusy then
      throw new RuntimeException(s"${getClass.getSimpleName} is busy; wait for idle before sendPrompt")
    firePrompt(text, promptAsFile)
    // Until the TUI shows a busy marker, [[isIdle]] can still be true; callers (and actors) rely on seeing busy.
    awaitBusy(defaultRunTimeout)

  override def start(prompt: String | Null): Unit =
    val optPrompt = Option(prompt)
    config.whichExecutable(agentBinaryName) match
      case None =>
        val msg =
          s"error: `$agentBinaryName` not found on PATH (install the agent CLI if needed)"
        config.err.println(msg)
        pane = None
        throw new RuntimeException(msg)
      case Some(bin) =>
        val tmux = config.newTmuxServer(tmuxSocket)

        cleanupAllPromptFiles()
        pane = None
        var promptPathOpt: Option[os.Path] = None
        try
          optPrompt.foreach { pr =>
            os.makeDir.all(promptStagingDir)
            val nio = Files.createTempFile(
              promptStagingDir.toNIO,
              "agents4s-prompt-",
              ".md"
            )
            val path = os.Path(nio)
            os.write.over(path, pr)
            promptPaths += path
            promptPathOpt = Some(path)
          }

          if !config.quiet then
            config.out.println(s"[$label] starting agent in tmux ...")
            config.out.println(s"[$label] attach with:  tmux -L $tmuxSocket attach -t $label")

          val cmd = buildCommand(bin)
          val target = tmux.newSession(label, workspacePath, cmd)
          pane = Some(config.newPane(tmuxSocket, target))

          optPrompt match
            case None => ()
            case Some(_) =>
              val pathStr = promptPathOpt.get.toString
              val shortPrompt = s"Read and follow the instructions in $pathStr"
              sendPrompt(shortPrompt, promptAsFile = false)
              awaitIdle(defaultRunTimeout)
              if !config.quiet then config.out.println(s"[$label] done.")
        catch
          case e: TimeoutException =>
            config.err.println(s"[$label] timeout: ${e.getMessage}")
            throw e
          case e: RuntimeException =>
            throw e
          case e: Exception =>
            config.err.println(s"[$label] error: ${e.getMessage}")
            throw new RuntimeException(e)
        finally
          promptPathOpt.foreach(discardPromptFile)
          if oneShot then stop()
        end try
    end match
  end start

end TmuxAgent
