package cursordriver

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PaneTraitTest extends AnyFunSuite with Matchers:

  test("captureEntireScrollback delegates to capturePane(start = -10)") {
    var capturedStart: Option[Int] = None
    val p = new Pane:
      def capturePane(start: Int = -10): Seq[String] =
        capturedStart = Some(start)
        Seq("a", "b")
      def sendKeys(keys: String, enter: Boolean = false): Unit = ()

    p.captureEntireScrollback() shouldBe Seq("a", "b")
    capturedStart shouldBe Some(-10)
  }

  test("sendKeys default enter is false") {
    var enterSeen: Option[Boolean] = None
    val p = new Pane:
      def capturePane(start: Int = -10): Seq[String] = Seq.empty
      def sendKeys(keys: String, enter: Boolean = false): Unit =
        enterSeen = Some(enter)

    p.sendKeys("x")
    enterSeen shouldBe Some(false)
  }

end PaneTraitTest
