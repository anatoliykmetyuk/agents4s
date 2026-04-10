package cursordriver

import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CursorAgentUnitTest extends AnyFunSuite with Matchers:

  private val F = TuiOps.FooterMarker
  private val B = TuiOps.BusyMarker
  private val T = TuiOps.TrustMarker

  private def trueOnPath: Option[String] =
    Paths.which("true").orElse(Some("/bin/true"))

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
          n.startsWith("cursor4s-prompt-") && n.endsWith(".md")
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

  test("minimal constructor exposes default public settings") {
    val tmp = tmpWorkspace
    val agent = new CursorAgent(tmp, "composer-2")
    agent.workspace shouldBe tmp
    agent.model shouldBe "composer-2"
    agent.tmuxSocket shouldBe "cursor-agent"
    agent.label shouldBe "agent"
    agent.quiet shouldBe false
    agent.killSession shouldBe true
  }

  test("awaitReady and awaitDone use default timeout when pane settles immediately") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp)
    val donePane = new MockPane(Seq(Seq(F)))
    agent.pane = Some(donePane)
    agent.awaitDone()
    val readyPane = new MockPane(Seq(Seq(F)))
    agent.pane = Some(readyPane)
    agent.awaitReady()
  }

  test("isTrustPrompt isReady isBusy use tail with multiline pane") {
    val tmp = tmpWorkspace
    val agent = unitAgent(tmp)
    val trustTail = (Seq.fill(19)("pad") :+ T).toSeq
    agent.pane = Some(new MockPane(Seq(trustTail)))
    agent.isTrustPrompt shouldBe true
    agent.isReady shouldBe false

    val readyTail = (Seq.fill(19)("pad") :+ F).toSeq
    agent.pane = Some(new MockPane(Seq(readyTail)))
    agent.isReady shouldBe true
    agent.isTrustPrompt shouldBe false
    agent.isBusy shouldBe false

    val busyTail = (Seq.fill(19)("pad") :+ B).toSeq
    agent.pane = Some(new MockPane(Seq(busyTail)))
    agent.isBusy shouldBe true
  }

  test("stop with killRemoteOnStop invokes tmux killSession") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("sock")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "sock",
      label = "my-lab",
      killSession = false,
      killRemoteOnStop = true,
      newTmuxServer = _ => rec,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )
    agent.pane = Some(new MockPane(Seq(Seq(F))))
    agent.stop()
    rec.killSessionNames.toList shouldBe List("my-lab")
  }

  test("stop on dead agent does not kill session or clear nonexistent pane") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("sock")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "sock",
      label = "my-lab",
      killSession = false,
      killRemoteOnStop = true,
      newTmuxServer = _ => rec,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )
    agent.pane shouldBe empty
    agent.stop()
    rec.killSessionNames shouldBe empty
    agent.pane shouldBe empty
  }

  test("stop on alive idle agent skips interrupt") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("sock")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "sock",
      label = "lab",
      killSession = false,
      killRemoteOnStop = false,
      newTmuxServer = _ => rec,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )
    val pane = new MockPane(Seq((Seq.fill(19)("pad") :+ F).toSeq))
    agent.pane = Some(pane)
    agent.stop()
    pane.sendInterruptCount shouldBe 0
    agent.pane shouldBe empty
  }

  test("stop on alive busy agent sends interrupt until idle") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("sock")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "sock",
      label = "lab",
      killSession = false,
      killRemoteOnStop = true,
      newTmuxServer = _ => rec,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )
    val busyTail = (Seq.fill(19)("pad") :+ B).toSeq
    val readyTail = (Seq.fill(19)("pad") :+ F).toSeq
    val pane = new MockPane(Seq(busyTail, readyTail))
    agent.pane = Some(pane)
    agent.stop(interruptAttempts = 10)
    pane.sendInterruptCount should be > 0
    rec.killSessionNames.toList shouldBe List("lab")
    agent.pane shouldBe empty
  }

  test("stop on alive busy agent exhausts interrupt attempts then tears down") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("sock")
    val max = 4
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "sock",
      label = "lab",
      killSession = false,
      killRemoteOnStop = true,
      newTmuxServer = _ => rec,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )
    val busyTail = (Seq.fill(19)("pad") :+ B).toSeq
    val pane = new MockPane(Seq(busyTail))
    agent.pane = Some(pane)
    agent.stop(interruptAttempts = max)
    pane.sendInterruptCount shouldBe max
    rec.killSessionNames.toList shouldBe List("lab")
    agent.pane shouldBe empty
  }

  test("stop when pane capture throws still tears down") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("sock")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "sock",
      label = "lab",
      killSession = false,
      killRemoteOnStop = true,
      newTmuxServer = _ => rec,
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      postSendKeysPause = _ => ()
    )
    val pane = new Pane:
      def capturePane(start: Int = -10): Seq[String] =
        throw new RuntimeException("capture failed")
      def sendKeys(keys: String, enter: Boolean = false): Unit = ()
      def sendInterrupt(): Unit = ()

    agent.pane = Some(pane)
    agent.stop()
    rec.killSessionNames.toList shouldBe List("lab")
    agent.pane shouldBe empty
  }

  test("start without prompt uses newPane and returns 0") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("soc")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "soc",
      label = "lab",
      quiet = true,
      killSession = false,
      killRemoteOnStop = false,
      whichExecutable = _ => trueOnPath,
      postSendKeysPause = _ => (),
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      newTmuxServer = _ => rec,
      newPane = (_, _) => new MockPane(Seq(Seq(F))),
      err = new PrintStream(OutputStream.nullOutputStream())
    )
    agent.start(None) shouldBe 0
    agent.pane.nonEmpty shouldBe true
    rec.killSessionNames shouldBe empty
  }

  test("start with killSession stops tmux session via newTmuxServer") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("soc")
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "soc",
      label = "lab",
      quiet = true,
      killSession = true,
      killRemoteOnStop = true,
      whichExecutable = _ => trueOnPath,
      postSendKeysPause = _ => (),
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      newTmuxServer = _ => rec,
      newPane = (_, _) => new MockPane(Seq(Seq(F))),
      err = new PrintStream(OutputStream.nullOutputStream())
    )
    agent.start(None) shouldBe 0
    rec.killSessionNames should contain("lab")
    agent.pane shouldBe empty
  }

  test("start with prompt discards staging file in finally") {
    val tmp = tmpWorkspace
    val rec = new RecordingTmuxServer("soc")
    val frames = Seq(
      Seq(F),
      Seq(F),
      Seq(F),
      Seq(s"$F\n$B"),
      Seq(F)
    )
    val agent = new CursorAgent(
      tmp,
      "composer-2",
      tmuxSocket = "soc",
      label = "lab",
      quiet = true,
      killSession = false,
      killRemoteOnStop = false,
      whichExecutable = _ => trueOnPath,
      postSendKeysPause = _ => (),
      tuiConfig = TuiConfig(pollIntervalS = 0.0, sleeper = _ => ()),
      newTmuxServer = _ => rec,
      newPane = (_, _) => new MockPane(frames),
      err = new PrintStream(OutputStream.nullOutputStream())
    )
    globPrompts(tmp) shouldBe empty
    agent.start(Some("prompt-body")) shouldBe 0
    globPrompts(tmp) shouldBe empty
    agent.promptPaths shouldBe empty
  }

end CursorAgentUnitTest
