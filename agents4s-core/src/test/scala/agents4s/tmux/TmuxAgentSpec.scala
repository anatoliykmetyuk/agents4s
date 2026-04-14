package agents4s.tmux

import java.nio.file.Path

import scala.concurrent.duration.*

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

private final class FakeTmuxAgent(
    val workspace: Path,
    var started: Boolean,
    var busy: Boolean,
    val mockPane: MockPane
) extends TmuxAgent:

  def socket: String = "test-socket"
  def label: String = "test-label"
  val startCommand: Seq[String] = Seq("true")
  def model: String = "test-model"

  override def isStarted: Boolean = started
  override def isBusy: Boolean = busy

  override def pane: Pane = mockPane

  override def start(): Unit =
    started = true

  override def stop(): Unit =
    started = false

  /** Avoid blocking when the fake never transitions to busy. */
  override def awaitBusy(
      timeout: Duration,
      pollInterval: Duration = 1.second
  ): Unit = ()

end FakeTmuxAgent

class TmuxAgentSpec extends AnyFunSuite with Matchers:

  test("sendPrompt throws when not started") {
    val tmp = java.nio.file.Files.createTempDirectory("tmux-agent-spec")
    try
      val mock = new MockPane(Seq(Seq("")))
      val agent = new FakeTmuxAgent(tmp, started = false, busy = false, mock)
      val ex = intercept[RuntimeException]:
        agent.sendPrompt("hello", promptAsFile = false)
      ex.getMessage should include("not started")
      mock.sendKeysCalls shouldBe empty
    finally java.nio.file.Files.deleteIfExists(tmp)
  }

  test("sendPrompt does not throw when busy") {
    val tmp = java.nio.file.Files.createTempDirectory("tmux-agent-spec")
    try
      val mock = new MockPane(Seq(Seq("")))
      val agent = new FakeTmuxAgent(tmp, started = true, busy = true, mock)
      agent.sendPrompt("second prompt", promptAsFile = false)
      mock.sendKeysCalls shouldBe Seq(
        ("second prompt", false),
        ("", true)
      )
    finally java.nio.file.Files.deleteIfExists(tmp)
  }

  test("sendPrompt sends keys when started and idle (awaitBusy no-op)") {
    val tmp = java.nio.file.Files.createTempDirectory("tmux-agent-spec")
    try
      val mock = new MockPane(Seq(Seq("")))
      val agent = new FakeTmuxAgent(tmp, started = true, busy = false, mock)
      agent.sendPrompt("idle prompt", promptAsFile = false)
      mock.sendKeysCalls shouldBe Seq(
        ("idle prompt", false),
        ("", true)
      )
    finally java.nio.file.Files.deleteIfExists(tmp)
  }

end TmuxAgentSpec
