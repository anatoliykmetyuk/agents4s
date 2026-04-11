package agents4s.pekko

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import agents4s.cursor.CursorAgent
import agents4s.tmux.Paths

import org.scalatest.Assertions.{assume, fail}
import org.scalatest.concurrent.TimeLimits
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Span
import org.scalatest.time.SpanSugar.*

import upickle.default.*
import upickle.jsonschema.*

/** Live Cursor `agent` + tmux; run with `scripts/test.sh -i` (see [[gate]]). */
class LLMActorIntegrationSpec extends AnyFunSuite with Matchers with TimeLimits:

  private val model: String =
    sys.env.getOrElse("CURSOR_DRIVER_MODEL", "composer-2-fast")

  private def integrationEnvSet: Boolean =
    sys.env.get("CURSOR_DRIVER_INTEGRATION").map(_.trim.toLowerCase).exists(Set("1", "true", "yes"))

  private def gate(): Unit =
    assume(integrationEnvSet, "Set CURSOR_DRIVER_INTEGRATION=1 to run live LLMActor tests")
    assume(Paths.which("agent").nonEmpty, "Cursor `agent` CLI not on PATH")
    assume(Paths.which("tmux").nonEmpty, "tmux not on PATH")

  private def tmpWorkspace: java.nio.file.Path =
    Files.createTempDirectory("llm-actor-int")

  private def uniqueSessionIds: (String, String) =
    val u = UUID.randomUUID().toString.replace("-", "").take(12)
    (s"llm-act-$u", s"la-$u")

  private def withTimeout[T](body: => T): T =
    failAfter(Span(100, org.scalatest.time.Seconds))(body)

  /** Workspace writes can lag the JSON result step slightly on a live agent. */
  private def waitForRegularFile(path: java.nio.file.Path, maxWaitMs: Long = 100_000L): Boolean =
    val deadline = System.nanoTime() + maxWaitMs * 1_000_000L
    while System.nanoTime() < deadline do
      if Files.isRegularFile(path) then return true
      Thread.sleep(250L)
    false

  import scala.concurrent.duration.* // for probe.receiveMessage(100.seconds)

  case class SimpleResult(answer: String) derives ReadWriter
  given JsonSchema[SimpleResult] = JsonSchema.derived

  case class NumericResult(n: Int) derives ReadWriter
  given JsonSchema[NumericResult] = JsonSchema.derived

  case class BoolResult(ok: Boolean) derives ReadWriter
  given JsonSchema[BoolResult] = JsonSchema.derived

  case class PersonInfo(name: String, age: Int) derives ReadWriter
  given JsonSchema[PersonInfo] = JsonSchema.derived

  case class FileTaskResult(path: String, created: Boolean) derives ReadWriter
  given JsonSchema[FileTaskResult] = JsonSchema.derived

  test("IT1 simple string answer in JSON") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    val kit = ActorTestKit()
    try
      withTimeout:
        val probe = kit.createTestProbe[SimpleResult | LLMActor.LLMError]()
        kit.spawn(
          LLMActor.start[SimpleResult](
            probe.ref,
            agent,
            "What is 2 + 2? Be concise.",
            "Put only the digits of the numeric answer in field answer (as a string)."
          )
        )
        probe.receiveMessage(100.seconds) match
          case SimpleResult(a)      => a should include("4")
          case LLMActor.LLMError(e) => fail("LLM pipeline failed", e)
    finally
      agent.stop()
      kit.shutdownTestKit()
  }

  test("IT2 integer field") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    val kit = ActorTestKit()
    try
      withTimeout:
        val probe = kit.createTestProbe[NumericResult | LLMActor.LLMError]()
        kit.spawn(
          LLMActor.start[NumericResult](
            probe.ref,
            agent,
            "Compute 7 * 8. Output nothing until the JSON step.",
            "Field n must be the integer 56."
          )
        )
        probe.receiveMessage(100.seconds) match
          case NumericResult(n)     => n shouldBe 56
          case LLMActor.LLMError(e) => fail("LLM pipeline failed", e)
    finally
      agent.stop()
      kit.shutdownTestKit()
  }

  test("IT3 boolean field") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    val kit = ActorTestKit()
    try
      withTimeout:
        val probe = kit.createTestProbe[BoolResult | LLMActor.LLMError]()
        kit.spawn(
          LLMActor.start[BoolResult](
            probe.ref,
            agent,
            "Is water typically wet? Answer yes or no in reasoning only.",
            "Field ok is true if the correct answer is yes."
          )
        )
        probe.receiveMessage(100.seconds) match
          case BoolResult(ok)       => ok shouldBe true
          case LLMActor.LLMError(e) => fail("LLM pipeline failed", e)
    finally
      agent.stop()
      kit.shutdownTestKit()
  }

  test("IT4 multi-field PersonInfo") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    val kit = ActorTestKit()
    try
      withTimeout:
        val probe = kit.createTestProbe[PersonInfo | LLMActor.LLMError]()
        kit.spawn(
          LLMActor.start[PersonInfo](
            probe.ref,
            agent,
            "The person's name is Alice Example and age is 30 years.",
            "Echo name exactly as 'Alice Example' and age as 30 in the JSON fields."
          )
        )
        probe.receiveMessage(100.seconds) match
          case PersonInfo(name, age) =>
            name should include("Alice")
            age shouldBe 30
          case LLMActor.LLMError(e) => fail("LLM pipeline failed", e)
    finally
      agent.stop()
      kit.shutdownTestKit()
  }

  test("IT5 create file in workspace and report in JSON") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val tok = UUID.randomUUID().toString.replace("-", "").take(10)
    val proof = tmp.resolve(s"llm_actor_proof_$tok.txt")
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    val kit = ActorTestKit()
    try
      withTimeout:
        val probe = kit.createTestProbe[FileTaskResult | LLMActor.LLMError]()
        kit.spawn(
          LLMActor.start[FileTaskResult](
            probe.ref,
            agent,
            s"""Create a UTF-8 file at exactly this path with one line containing HELLO-$tok:
               |$proof
               |Then finish.""".stripMargin,
            s"""Write JSON with path = absolute path string of that file (must match $proof),
 |and created = true if the file exists with HELLO-$tok inside.""".stripMargin
          )
        )
        probe.receiveMessage(100.seconds) match
          case FileTaskResult(path, _) =>
            val proofStr = proof.toAbsolutePath.normalize.toString
            path should (include(proofStr) or include(proof.toString))
            waitForRegularFile(proof) shouldBe true
            Files.readString(proof, StandardCharsets.UTF_8) should include(s"HELLO-$tok")
          case LLMActor.LLMError(e) => fail("LLM pipeline failed", e)
    finally
      agent.stop()
      kit.shutdownTestKit()
  }

  test("IT6 non-JSON file content yields LLMError after retries") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    val kit = ActorTestKit()
    try
      withTimeout:
        val probe = kit.createTestProbe[NumericResult | LLMActor.LLMError]()
        kit.spawn(
          LLMActor.start[NumericResult](
            probe.ref,
            agent,
            "You are being tested for robustness.",
            """IGNORE the schema. Write only the plain text: NO JSON NO BRACES
 |in the result file. No digits.""".stripMargin
          )
        )
        probe.receiveMessage(100.seconds) match
          case LLMActor.LLMError(_) => ()
          case other =>
            assume(
              condition = false,
              s"Expected LLMError after invalid JSON retries; got success $other (model may have still produced valid JSON)"
            )
    finally
      agent.stop()
      kit.shutdownTestKit()
  }

end LLMActorIntegrationSpec
