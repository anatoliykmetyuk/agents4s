package agents4s.tmux

import java.util.regex.Pattern
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object TmuxServer:

  private val ansiRe: Pattern = Pattern.compile("\u001b\\[[\\d;]*[A-Za-z]")

  def stripAnsi(text: String): String =
    ansiRe.matcher(text).replaceAll("")

  /** Cached `id -u` for default tmux socket path layout. */
  lazy val unixUserId: String =
    runProcess(Seq("id", "-u"), check = true)._2.trim

  private[tmux] def runProcess(cmd: Seq[String], check: Boolean): (Int, String) =
    val pb = new ProcessBuilder(cmd*)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val stdout = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val exit = proc.waitFor()
    if check && exit != 0 then
      throw new RuntimeException(
        s"command failed: ${cmd.mkString(" ")} (exit $exit)\n$stdout"
      )
    (exit, stdout)

end TmuxServer

class TmuxServer(socketName: String):

  private def tmux(extra: String*): Seq[String] =
    Seq("tmux", "-L", socketName) ++ extra

  private def run(cmd: Seq[String], check: Boolean = true): (Int, String) =
    TmuxServer.runProcess(cmd, check)

  /** Path to the tmux server socket file for this `-L` name (if the server were running). */
  private def socketFile: Path =
    val base = sys.env.get("TMUX_TMPDIR").map(Path.of(_)).getOrElse(Path.of("/tmp"))
    base.resolve(s"tmux-${TmuxServer.unixUserId}").resolve(socketName)

  def socketExists: Boolean = Files.exists(socketFile)

  def hasSession(sessionName: String): Boolean =
    socketExists && run(tmux("has-session", "-t", sessionName), check = false)._1 == 0

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

  private def run(cmd: Seq[String]): (Int, String) =
    TmuxServer.runProcess(cmd, check = true)

  override def capturePane(start: Int = -10): Seq[String] =
    val (_, text) = run(tmux("capture-pane", "-p", "-t", target, "-S", start.toString))
    if text.isEmpty then Seq.empty
    else
      val raw = text.stripTrailing()
      if raw.isEmpty then Seq("")
      else raw.split("\n", -1).toSeq

  override def captureEntireScrollback(): Seq[String] =
    val (_, text) = run(tmux("capture-pane", "-p", "-t", target, "-S", "-", "-E", "-"))
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
    val (code, out) = TmuxServer.runProcess(
      Seq("sh", "-c", s"command -v $name"),
      check = false
    )
    if code == 0 then Some(out.trim)
    else None

end Paths
