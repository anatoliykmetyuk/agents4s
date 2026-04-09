package cursordriver

import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CursorAgentUnitTest extends AnyFunSuite with Matchers:

  private val F = TuiOps.FooterMarker
  private val B = TuiOps.BusyMarker

  private def unitAgent(
      workspace: os.Path,
      model: String = "composer-2",
      killSession: Boolean = false
  ): CursorAgent =
    new CursorAgent(
      workspace,
      model,
      killSession = killSession,
      killRemoteOnStop = false,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )

  private def tmpWorkspace: os.Path =
    os.Path(Files.createTempDirectory("cursor-agent-unit"))

  private def globPrompts(root: os.Path): Seq[os.Path] =
    val d = root / ".cursor" / "prompts"
    if os.isDir(d) then
      os.list(d)
        .filter { p =>
          val n = p.last
          n.startsWith("cursor-driver-prompt-") && n.endsWith(".md")
        }
        .toSeq
        .sortBy(_.toString)
    else Seq.empty

  test("requirePane methods before start raise") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp, killSession = true)
    intercept[RuntimeException](agent.isReady).getMessage should include("not started")
    intercept[RuntimeException](agent.isBusy).getMessage should include("not started")
    intercept[RuntimeException](agent.isTrustPrompt).getMessage should include("not started")
    intercept[RuntimeException](agent.awaitReady(timeoutS = 1.0)).getMessage should include(
      "not started"
    )
    intercept[RuntimeException](agent.sendPrompt("hi")).getMessage should include("not started")
    agent.stop()
  }

  test("stop is safe before start and clears pane") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp, killSession = false)
    agent.stop()
    agent.pane shouldBe empty
    val pane = new MockPane(Seq(Seq(F)))
    agent.pane = Some(pane)
    agent.stop()
    agent.pane shouldBe empty
  }

  test("start returns 127 when agent not on path") {
    val tmp = tmpWorkspace
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      killSession = true,
      killRemoteOnStop = false,
      whichExecutable = _ => None,
      err = new PrintStream(OutputStream.nullOutputStream())
    )
    agent.start() shouldBe 127
    agent.pane shouldBe empty
  }

  test("initializer stores attributes") {
    val tmp = tmpWorkspace
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "s",
      label = "l",
      quiet = true,
      killSession = false,
      killRemoteOnStop = false
    )
    agent.workspace shouldBe tmp
    agent.model shouldBe "composer-2"
    agent.tmuxSocket shouldBe "s"
    agent.label shouldBe "l"
    agent.quiet shouldBe true
    agent.killSession shouldBe false
  }

  test("sendPrompt ordering after start") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp, killSession = false)
    val pane = new MockPane(Seq(Seq(F), Seq(s"$F\n$B")))
    agent.pane = Some(pane)
    agent.sendPrompt("hello world", promptAsFile = false)
    pane.sendKeysCalls(0) shouldBe (("hello world", false))
    pane.sendKeysCalls(1) shouldBe (("", true))
  }

  test("sendPrompt as file creates temp and sends read instruction") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp, killSession = false)
    val pane = new MockPane(Seq(Seq(F), Seq(F), Seq(s"$F\n$B")))
    agent.pane = Some(pane)
    val body = "some long text with\nnewlines and unicode: é"
    agent.sendPrompt(body, promptAsFile = true)

    val matches = globPrompts(tmp)
    matches should have size 1
    val promptPath = matches.head
    os.read(promptPath) shouldBe body
    agent.promptPaths.toList shouldBe List(promptPath)

    pane.sendKeysCalls(0)._1 shouldBe s"Read and follow the instructions in $promptPath"
    pane.sendKeysCalls(0)._2 shouldBe false
    pane.sendKeysCalls(1) shouldBe (("", true))
  }

  test("sendPrompt as file cleanup removes tracked files") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp, killSession = false)
    val pane = new MockPane(
      Seq(
        Seq(F),
        Seq(F),
        Seq(s"$F\n$B"),
        Seq(F),
        Seq(F),
        Seq(s"$F\n$B")
      )
    )
    agent.pane = Some(pane)
    agent.sendPrompt("first", promptAsFile = true)
    agent.sendPrompt("second", promptAsFile = true)
    val paths = globPrompts(tmp)
    paths should have size 2
    paths.map(os.read(_)).toSet shouldBe Set("first", "second")

    agent.stop()

    globPrompts(tmp) shouldBe empty
    agent.promptPaths shouldBe empty
  }

end CursorAgentUnitTest
