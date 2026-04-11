package agents4s.tmux

import agents4s.Agent

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

/** Shared tmux session lifecycle; subclasses supply binary, command line, and TUI semantics. */
trait TmuxAgent extends Agent:

  private var _pane: TmuxPane | Null = null
  private var _tmuxServer: TmuxServer | Null = null

  def socket: String
  def label: String

  def pane: Pane = _pane
  def tmuxServer: TmuxServer = _tmuxServer

  val startCommand: Seq[String]

  override def start(): Unit =
    _tmuxServer = TmuxServer(socket)
    val cmd = startCommand
    val target = tmuxServer.newSession(label, workspace, cmd)
    _pane = TmuxPane(socket, target)

  override def stop(): Unit =
    tmuxServer.killSession(label)
    _pane = null
    _tmuxServer = null

  override def sendPrompt(text: String, promptAsFile: Boolean): Unit =
    if !isStarted then
      throw new RuntimeException(s"${getClass.getSimpleName} is not started; call start() first")
    if isBusy then
      throw new RuntimeException(
        s"${getClass.getSimpleName} is busy; wait for idle before sendPrompt"
      )

    val textToSend =
      if promptAsFile then
        val promptFile = Files.createTempFile(s"agents4s-$socket-$label-prompt-", ".md")
        Files.write(promptFile, text.getBytes(StandardCharsets.UTF_8))
        s"Read and follow the instructions in $promptFile"
      else text
    pane.sendKeys(textToSend, enter = false)
    Thread.sleep(200)
    pane.sendKeys("", enter = true)
