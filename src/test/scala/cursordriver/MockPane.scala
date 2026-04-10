package cursordriver

import scala.collection.mutable

/** Deterministic [[Pane]] stand-in for waiter tests (Python test_tui_ops.MockPane). */
final class MockPane(frames: Seq[Seq[String]]) extends Pane:

  private var n: Int = 0
  val sendKeysCalls: mutable.Buffer[(String, Boolean)] = mutable.ArrayBuffer.empty
  var sendInterruptCount: Int = 0

  override def capturePane(start: Int = -10): Seq[String] =
    val out =
      if n < frames.size then
        val f = frames(n)
        n += 1
        f
      else frames.last
    out.toSeq

  override def sendKeys(keys: String, enter: Boolean = false): Unit =
    sendKeysCalls += ((keys, enter))

  override def sendInterrupt(): Unit =
    sendInterruptCount += 1

end MockPane
