package cursordriver

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PathsTest extends AnyFunSuite with Matchers:

  test("which finds sh on PATH") {
    Paths.which("sh") should not be empty
  }

  test("which returns None for nonexistent command") {
    Paths.which("__nonexistent_cursor_driver__") shouldBe empty
  }

end PathsTest
