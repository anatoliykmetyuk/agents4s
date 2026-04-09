package cursordriver

import scala.collection.mutable

/** Test double: no shell tmux calls; records [[killSession]] invocations. */
final class RecordingTmuxServer(socketName: String) extends TmuxServer(socketName):

  val killSessionNames: mutable.Buffer[String] = mutable.ArrayBuffer.empty

  override def killSession(sessionName: String): Unit =
    killSessionNames += sessionName

  override def hasSession(sessionName: String): Boolean = false

  override def socketExists: Boolean = false

  override def newSession(
      sessionName: String,
      startDirectory: os.Path,
      command: Seq[String]
  ): String =
    s"$sessionName:0.0"

end RecordingTmuxServer
