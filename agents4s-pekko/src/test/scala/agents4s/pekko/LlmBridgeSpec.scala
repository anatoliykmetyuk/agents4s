package agents4s.pekko

import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files

import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

import agents4s.cursor.CursorAgent
import agents4s.cursor.CursorTuiOps
import agents4s.tmux.{AgentConfig, Pane}

class LlmBridgeSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  private val F = CursorTuiOps.FooterMarker

  final case class In(workspace: os.Path, outFileName: String = "out.json")
  final case class Out(status: String)

  private final class SpecPane(footerLine: String) extends Pane:
    override def capturePane(start: Int = -10): Seq[String] = Seq(footerLine)
    override def sendKeys(keys: String, enter: Boolean = false): Unit = ()
    override def sendInterrupt(): Unit = ()

  /** Test double: [[start]](`None`) installs a pane; [[isBusy]] simulates one tick of work per
    * [[firePrompt]].
    */
  class ScriptedHeartbeatAgent(
      workspace: os.Path,
      resultPath: os.Path
  ) extends CursorAgent(
        workspace,
        "test-model",
        oneShot = false,
        config = AgentConfig(
          pollIntervalS = 0.0,
          sleeper = _ => (),
          killRemoteOnStop = false,
          postSendKeysPause = _ => (),
          whichExecutable = _ => Some("/bin/true"),
          err = new PrintStream(OutputStream.nullOutputStream()),
          quiet = true
        )
      ):
    private val mockPane = new SpecPane(F)

    private var busyRemaining: Int = 0

    override def start(prompt: Option[String]): Int =
      prompt match
        case None =>
          pane = Some(mockPane)
          0
        case Some(_) =>
          127

    override def isReady: Boolean = busyRemaining == 0

    override def isBusy: Boolean =
      if busyRemaining > 0 then
        busyRemaining -= 1
        true
      else false

    override def firePrompt(text: String, promptAsFile: Boolean): Unit =
      busyRemaining = 1
      if !promptAsFile then
        os.makeDir.all(resultPath / os.up)
        os.write.over(resultPath, """{"status":"ok"}""")
      super.firePrompt(text, promptAsFile)

  object TestBridge extends LlmBridge[In, Out, ScriptedHeartbeatAgent]:
    override def heartbeatInterval: scala.concurrent.duration.FiniteDuration = 20.millis

    override def createAgent(input: In): ScriptedHeartbeatAgent =
      new ScriptedHeartbeatAgent(input.workspace, outputPath(input))

    override def buildPrompt(input: In): String =
      s"work step for ${input.outFileName}"

    override def outputPath(input: In): os.Path =
      input.workspace / input.outFileName

    override def parseOutput(raw: String): Out =
      if raw.contains("\"status\"") && raw.contains("ok") then Out("ok")
      else throw new IllegalArgumentException("unexpected output")

  object FailingStartBridge extends LlmBridge[In, Out, ScriptedHeartbeatAgent]:
    override def heartbeatInterval: scala.concurrent.duration.FiniteDuration = 20.millis

    final class AlwaysFailingStart(ws: os.Path, out: os.Path)
        extends ScriptedHeartbeatAgent(ws, out):
      override def start(prompt: Option[String]): Int = 127

    override def createAgent(input: In): ScriptedHeartbeatAgent =
      new AlwaysFailingStart(input.workspace, outputPath(input))

    override def buildPrompt(input: In): String = "n/a"

    override def outputPath(input: In): os.Path = input.workspace / input.outFileName

    override def parseOutput(raw: String): Out =
      throw new UnsupportedOperationException("n/a")

  "LlmBridge" should {
    "run work prompt, follow-up, read JSON, and reply" in {
      val ws = os.Path(Files.createTempDirectory("llm-bridge-spec"))
      val probe = createTestProbe[Out]()
      val ref = spawn(TestBridge.behavior(probe.ref))
      ref ! In(ws, "out.json")
      probe.expectMessage(Out("ok"))
    }

    "ignore a second In while busy" in {
      val ws = os.Path(Files.createTempDirectory("llm-bridge-spec-2"))
      val p1 = createTestProbe[Out]()
      val p2 = createTestProbe[Out]()
      val ref = spawn(TestBridge.behavior(p1.ref))
      ref ! In(ws, "a.json")
      ref ! In(ws, "b.json")
      p2.expectNoMessage(100.millis)
      p1.expectMessage(Out("ok"))
    }

    "send no reply when agent start fails" in {
      val ws = os.Path(Files.createTempDirectory("llm-bridge-spec-3"))
      val probe = createTestProbe[Out]()
      val ref = spawn(FailingStartBridge.behavior(probe.ref))
      ref ! In(ws, "out.json")
      probe.expectNoMessage(200.millis)
    }
  }

end LlmBridgeSpec
