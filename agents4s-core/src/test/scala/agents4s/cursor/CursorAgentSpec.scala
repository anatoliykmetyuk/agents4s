package agents4s.cursor

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Regression and API-shape tests for [[CursorAgent]] lifecycle (trust handled in `start`, not
  * `isIdle`).
  */
class CursorAgentSpec extends AnyFunSuite with Matchers:

  test("CursorAgent overrides start for trust / ready handshake") {
    val startDecls =
      classOf[CursorAgent].getDeclaredMethods.filter(_.getName == "start").toSeq
    startDecls.nonEmpty shouldBe true
  }

end CursorAgentSpec
