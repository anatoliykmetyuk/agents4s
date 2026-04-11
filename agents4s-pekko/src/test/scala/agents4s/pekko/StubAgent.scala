package agents4s.pekko

import scala.collection.mutable

import agents4s.Agent

/** Test double for [[agents4s.Agent]] with scripted [[isBusy]] phases per [[sendPrompt]] round. */
final class StubAgent(
    val workspace: java.nio.file.Path,
    val model: String = "stub-model",
    busyPhases: List[Int] = List(0, 0),
    onSendPrompt: String => Unit = _ => ()
) extends Agent:

  private val startCalls = mutable.ArrayBuffer[Option[String]]()
  private val sendPromptCalls = mutable.ArrayBuffer[String]()

  private var phaseIdx = 0
  private var busyRemaining: Int = busyPhases.headOption.getOrElse(0)
  @volatile private var started = false

  def recordedStarts: Seq[Option[String]] = startCalls.toSeq
  def recordedSendPrompts: Seq[String] = sendPromptCalls.toSeq

  override def start(prompt: String | Null): Unit =
    started = true
    phaseIdx = 0
    busyRemaining = busyPhases.lift(0).getOrElse(0)
    startCalls += Option(prompt)

  override def stop(): Unit =
    started = false

  override def sendPrompt(text: String, promptAsFile: Boolean): Unit =
    if !started then throw new RuntimeException("StubAgent is not started; call start() first")
    if busyRemaining > 0 then
      throw new RuntimeException("StubAgent is busy; wait for idle before sendPrompt")
    sendPromptCalls += text
    onSendPrompt(text)
    phaseIdx += 1
    busyRemaining = busyPhases.lift(phaseIdx).getOrElse(0)

  override def isStarted: Boolean = started

  override def isBusy: Boolean =
    if !started then throw new RuntimeException("StubAgent is not started; call start() first")
    if busyRemaining > 0 then
      busyRemaining -= 1
      true
    else false

end StubAgent
