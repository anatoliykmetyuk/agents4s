package agents4s.cursor

import java.util.concurrent.TimeoutException

import scala.collection.mutable

import agents4s.tmux.{AgentConfig, MockPane, TmuxServer}

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.prop.Tables.Table

class CursorTuiOpsTest extends AnyFunSuite with Matchers with TableDrivenPropertyChecks:

  private val fastCfg = AgentConfig(pollIntervalS = 0.0, sleeper = _ => ())

  private val F = CursorTuiOps.FooterMarker
  private val B = CursorTuiOps.BusyMarker
  private val T = CursorTuiOps.TrustMarker

  test("stripAnsi empty") {
    TmuxServer.stripAnsi("") shouldBe ""
  }

  test("stripAnsi plain") {
    TmuxServer.stripAnsi("hello") shouldBe "hello"
  }

  test("stripAnsi sgr") {
    val raw = "\u001b[31mred\u001b[0m"
    TmuxServer.stripAnsi(raw) shouldBe "red"
  }

  test("stripAnsi complex sgr") {
    val raw = "\u001b[1;32mok\u001b[m"
    TmuxServer.stripAnsi(raw) shouldBe "ok"
  }

  test("predicate matrix") {
    val table = Table(
      ("text", "expTrust", "expReady", "expBusy"),
      ("", false, false, false),
      (F, false, true, false),
      (T, true, false, false),
      (B, false, false, true),
      (s"$F\n$B", false, false, true),
      (s"$F\n$T", false, true, false),
      (s"$T\n$B", true, false, true),
      (s"$F\n$T\n$B", false, false, true),
      (s"\u001b[31m$F\u001b[0m", false, true, false)
    )
    forAll(table) { (text, expTrust, expReady, expBusy) =>
      val plain = TmuxServer.stripAnsi(text)
      CursorTuiOps.isTrustPrompt(plain) shouldBe expTrust
      CursorTuiOps.isReady(plain) shouldBe expReady
      CursorTuiOps.isBusy(plain) shouldBe expBusy
    }
  }

  test("adversarial footer plus trust documents ready first branch") {
    val text = s"$F\n$T"
    CursorTuiOps.isReady(text) shouldBe true
    CursorTuiOps.isTrustPrompt(text) shouldBe false
  }

  test("R1 awaitReady already ready no sendKeys") {
    val pane = new MockPane(Seq(Seq(F)))
    CursorTuiOps.awaitReady(pane, timeoutS = 5.0)(using fastCfg)
    pane.sendKeysCalls shouldBe empty
  }

  test("R2 awaitReady blank then ready") {
    val pane = new MockPane(Seq(Seq("boot"), Seq("..."), Seq(F)))
    CursorTuiOps.awaitReady(pane, timeoutS = 5.0)(using fastCfg)
    pane.sendKeysCalls shouldBe empty
  }

  test("R3 awaitReady trust then ready sends a") {
    val pane = new MockPane(Seq(Seq(T), Seq(T), Seq(F)))
    CursorTuiOps.awaitReady(pane, timeoutS = 5.0)(using fastCfg)
    pane.sendKeysCalls.toSeq should contain(("a", false))
  }

  test("R4 awaitReady trust many polls then ready") {
    val trustLines = (1 to 8).map(_ => Seq(T)).toSeq
    val pane = new MockPane(trustLines :+ Seq(F))
    CursorTuiOps.awaitReady(pane, timeoutS = 5.0)(using fastCfg)
    pane.sendKeysCalls.toSeq should contain(("a", false))
  }

  test("R5 awaitReady never ready timeout") {
    val pane = new MockPane(Seq(Seq("waiting")))
    val e = intercept[TimeoutException] {
      CursorTuiOps.awaitReady(pane, timeoutS = 0.05)(using fastCfg)
    }
    e.getMessage should include("agent did not become ready in time")
  }

  test("R6 handleTrust timeout trust never clears") {
    val pane = new MockPane(Seq(Seq(T)))
    val e = intercept[TimeoutException] {
      CursorTuiOps.handleTrust(pane, timeoutS = 0.05)(using fastCfg)
    }
    e.getMessage should include("trust dialog did not dismiss")
  }

  test("H1 handleTrust first snapshot not trust returns after one a") {
    val pane = new MockPane(Seq(Seq(F)))
    CursorTuiOps.handleTrust(pane, timeoutS = 5.0)(using fastCfg)
    pane.sendKeysCalls.toSeq shouldBe Seq(("a", false))
  }

  test("H2 handleTrust trust clears immediately after a") {
    val pane = new MockPane(Seq(Seq(T), Seq(F)))
    CursorTuiOps.handleTrust(pane, timeoutS = 5.0)(using fastCfg)
    pane.sendKeysCalls.head shouldBe (("a", false))
  }

  test("H3 handleTrust never clears timeout") {
    val pane = new MockPane(Seq(Seq(T)))
    val e = intercept[TimeoutException] {
      CursorTuiOps.handleTrust(pane, timeoutS = 0.05)(using fastCfg)
    }
    e.getMessage should include("trust dialog did not dismiss")
  }

  test("B1 awaitBusy already busy") {
    val pane = new MockPane(Seq(Seq(B)))
    CursorTuiOps.awaitBusy(pane, timeoutS = 5.0)(using fastCfg)
  }

  test("B2 awaitReady then busy") {
    val pane = new MockPane(Seq(Seq(F), Seq(F), Seq(s"$F\n$B")))
    CursorTuiOps.awaitBusy(pane, timeoutS = 5.0)(using fastCfg)
  }

  test("B3 awaitBusy never busy timeout") {
    val pane = new MockPane(Seq(Seq(F)))
    val e = intercept[TimeoutException] {
      CursorTuiOps.awaitBusy(pane, timeoutS = 0.05)(using fastCfg)
    }
    e.getMessage should include("agent never started working")
  }

  test("D1 awaitDone not busy first poll returns immediately") {
    val pane = new MockPane(Seq(Seq(F)))
    CursorTuiOps.awaitDone(pane, timeoutS = 5.0)(using fastCfg)
  }

  test("D2 awaitDone busy then idle") {
    val pane = new MockPane(Seq(Seq(s"$F\n$B"), Seq(s"$F\n$B"), Seq(F)))
    CursorTuiOps.awaitDone(pane, timeoutS = 5.0)(using fastCfg)
  }

  test("D3 awaitDone stays busy timeout") {
    val pane = new MockPane(Seq(Seq(s"$F\n$B")))
    val e = intercept[TimeoutException] {
      CursorTuiOps.awaitDone(pane, timeoutS = 0.05)(using fastCfg)
    }
    e.getMessage should include("agent work exceeded")
  }

  test("tailText joins and strips ansi") {
    val pane = new MockPane(Seq(Seq("\u001b[31mx\u001b[0m", F)))
    val text = CursorTuiOps.tailText(pane, nLines = 10)(using fastCfg)
    text should not include ("\u001b")
    text should include(F)
  }

  test(
    "default timeout and nLines accessors: awaitReady awaitBusy awaitDone handleTrust tailText"
  ) {
    val r = new MockPane(Seq(Seq(F)))
    CursorTuiOps.awaitReady(r)(using fastCfg)
    val b = new MockPane(Seq(Seq(B)))
    CursorTuiOps.awaitBusy(b)(using fastCfg)
    val d = new MockPane(Seq(Seq(F)))
    CursorTuiOps.awaitDone(d)(using fastCfg)
    val h = new MockPane(Seq(Seq(F)))
    CursorTuiOps.handleTrust(h)(using fastCfg)
    val t = new MockPane(Seq(Seq("x", F)))
    CursorTuiOps.tailText(t)(using fastCfg) should include(F)
  }

  test("AgentConfig sleeper used when polling awaitReady") {
    var now = 0L
    val sleeps = mutable.ArrayBuffer[Double]()
    val cfg = AgentConfig(
      pollIntervalS = 1.0,
      sleeper = { d =>
        sleeps += d
        now += (d * 1e9).toLong
      },
      clockNanos = () => now
    )
    val pane = new MockPane(Seq(Seq("boot"), Seq(F)))
    CursorTuiOps.awaitReady(pane, timeoutS = 10.0)(using cfg)
    sleeps should not be empty
  }

end CursorTuiOpsTest
