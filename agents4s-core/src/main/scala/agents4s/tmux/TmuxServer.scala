package agents4s.tmux

import java.util.regex.Pattern
import java.nio.file.Path

import scala.language.implicitConversions

object TmuxServer:

  private val ansiRe: Pattern = Pattern.compile("\u001b\\[[\\d;]*[A-Za-z]")

  def stripAnsi(text: String): String =
    ansiRe.matcher(text).replaceAll("")

  /** Matches tmux: TMUX_TMPDIR or /tmp, then tmux-$(id -u), then -L socket file name. */
  private lazy val unixUserId: String =
    proc(Seq("id", "-u").map(s => s: Shellable)*).call().out.text().trim

end TmuxServer

class TmuxServer(socketName: String):

  private def tmux(extra: String*): Seq[String] =
    Seq("tmux", "-L", socketName) ++ extra

  private def run(cmd: Seq[String], check: Boolean = true): os.CommandResult =
    proc(cmd.map(s => s: Shellable)*).call(check = check)

  /** Path to the tmux server socket file for this `-L` name (if the server were running). */
  private def socketFile: Path =
    val base = sys.env.get("TMUX_TMPDIR").map(Path(_)).getOrElse(Path("/tmp"))
    base / s"tmux-${TmuxServer.unixUserId}" / socketName

  def socketExists: Boolean = os.exists(socketFile)

  def hasSession(sessionName: String): Boolean =
    socketExists && run(tmux("has-session", "-t", sessionName), check = false).exitCode == 0

  def killSession(sessionName: String): Unit =
    if hasSession(sessionName) then
      run(tmux("kill-session", "-t", sessionName), check = false): @annotation.nowarn

  /** Detached session with initial command; returns first window pane target `name:0.0`. */
  def newSession(sessionName: String, startDirectory: Path, command: Seq[String]): String =
    val prefix = tmux(
      "new-session",
      "-d",
      "-s",
      sessionName,
      "-c",
      startDirectory.toString
    )
    run(prefix ++ command)
    s"$sessionName:0.0"

end TmuxServer

final class TmuxPane(socketName: String, target: String) extends Pane:

  private def tmux(extra: String*): Seq[String] =
    Seq("tmux", "-L", socketName) ++ extra

  private def run(cmd: Seq[String]): os.CommandResult =
    proc(cmd.map(s => s: Shellable)*).call()

  override def capturePane(start: Int = -10): Seq[String] =
    val out = run(tmux("capture-pane", "-p", "-t", target, "-S", start.toString))
    val text = out.out.text()
    if text.isEmpty then Seq.empty
    else
      val raw = text.stripTrailing()
      if raw.isEmpty then Seq("")
      else raw.split("\n", -1).toSeq

  override def captureEntireScrollback(): Seq[String] =
    val out = run(tmux("capture-pane", "-p", "-t", target, "-S", "-", "-E", "-"))
    val text = out.out.text()
    if text.isEmpty then Seq.empty
    else text.stripTrailing().split("\n", -1).toSeq

  override def sendKeys(keys: String, enter: Boolean = false): Unit =
    if keys.nonEmpty then run(tmux("send-keys", "-t", target, "-l", keys))
    if enter then run(tmux("send-keys", "-t", target, "C-m"))

  override def sendInterrupt(): Unit =
    run(tmux("send-keys", "-t", target, "C-c"))

end TmuxPane

object Paths:

  /** Resolve first executable named `name` on PATH (like shutil.which). */
  def which(name: String): Option[String] =
    val res = proc(Seq("sh", "-c", s"command -v $name").map(s => s: Shellable)*).call(check = false)
    if res.exitCode == 0 then Some(res.out.text().trim)
    else None

end Paths
