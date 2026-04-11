package agents4s.pekko

import scala.collection.mutable

import agents4s.Agent

/** Test double for [[agents4s.Agent]] with scripted [[isBusy]] phases per [[sendPrompt]] round. */
final class StubAgent(
    val workspace: os.Path,
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

  override def start(prompt: Option[String]): Int =
    started = true
    phaseIdx = 0
    busyRemaining = busyPhases.lift(0).getOrElse(0)
    startCalls += prompt
    0

  override def stop(interruptAttempts: Int = 10): Unit =
    started = false

  override def sendPrompt(text: String, timeoutS: Double, promptAsFile: Boolean): Unit =
    if !started then throw new RuntimeException("StubAgent is not started; call start() first")
    sendPromptCalls += text
    onSendPrompt(text)
    phaseIdx += 1
    busyRemaining = busyPhases.lift(phaseIdx).getOrElse(0)

  override def isReady: Boolean = !isBusy

  override def isBusy: Boolean =
    if !started then throw new RuntimeException("StubAgent is not started; call start() first")
    if busyRemaining > 0 then
      busyRemaining -= 1
      true
    else false

  override def awaitReady(timeoutS: Double): Unit = ()

  override def awaitBusy(timeoutS: Double): Unit = ()

  override def awaitDone(timeoutS: Double): Unit = ()

end StubAgent
