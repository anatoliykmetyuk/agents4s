package agents4s.pekko

import java.nio.file.Files
import java.util.regex.Pattern

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import upickle.default.*
import upickle.jsonschema.*

class LLMActorSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  import scala.concurrent.duration.*

  override def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  private def tmpWorkspace: os.Path =
    os.Path(Files.createTempDirectory("llm-actor-spec").toFile)

  private val jsonPathPattern: Pattern =
    Pattern.compile("following path:\\s*(.+)\\s*", Pattern.MULTILINE)

  def extractJsonFilePath(prompt: String): os.Path =
    val m = jsonPathPattern.matcher(prompt)
    m.find() shouldBe true
    os.Path(m.group(1).trim)

  // --- Output types for tests (ReadWriter + JsonSchema) ---

  case class TestResult(value: String) derives ReadWriter
  given JsonSchema[TestResult] = JsonSchema.derived

  case class NestedResult(outer: TestResult, n: Int) derives ReadWriter
  given JsonSchema[NestedResult] = JsonSchema.derived

  case class LargePayload(
      a: String,
      b: Int,
      c: Boolean,
      d: String,
      e: Int
  ) derives ReadWriter
  given JsonSchema[LargePayload] = JsonSchema.derived

  test("happy path: immediate idle, valid JSON") {
    val ws = tmpWorkspace
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0),
      onSendPrompt = p =>
        val path = extractJsonFilePath(p)
        os.write.over(path, """{"value":"hello"}""")
    )
    val probe = testKit.createTestProbe[TestResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[TestResult](
        probe.ref,
        stub,
        "do work",
        "Put greeting in value"
      )
    )
    probe.expectMessage(30.seconds, TestResult("hello"))
    probe.expectTerminated(child, 5.seconds)
  }

  test("happy path: busy ticks before result prompt, then busy before read") {
    val ws = tmpWorkspace
    val stub = new StubAgent(
      ws,
      busyPhases = List(2, 1),
      onSendPrompt = p =>
        val path = extractJsonFilePath(p)
        os.write.over(path, """{"value":"delayed"}""")
    )
    val probe = testKit.createTestProbe[TestResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[TestResult](
        probe.ref,
        stub,
        "task",
        "instructions"
      )
    )
    probe.expectMessage(30.seconds, TestResult("delayed"))
    probe.expectTerminated(child, 5.seconds)
  }

  test("complex nested output type") {
    val ws = tmpWorkspace
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0),
      onSendPrompt = p =>
        val path = extractJsonFilePath(p)
        os.write.over(path, """{"outer":{"value":"in"},"n":42}""")
    )
    val probe = testKit.createTestProbe[NestedResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[NestedResult](
        probe.ref,
        stub,
        "nested",
        "nested out"
      )
    )
    probe.expectMessage(30.seconds, NestedResult(TestResult("in"), 42))
    probe.expectTerminated(child, 5.seconds)
  }

  test("retry: invalid JSON then valid on second attempt") {
    val ws = tmpWorkspace
    var attempt = 0
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0, 0),
      onSendPrompt = p =>
        attempt += 1
        val path = extractJsonFilePath(p)
        if attempt == 1 then os.write.over(path, "not json {{{")
        else os.write.over(path, """{"value":"fixed"}""")
    )
    val probe = testKit.createTestProbe[TestResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[TestResult](
        probe.ref,
        stub,
        "x",
        "y"
      )
    )
    probe.expectMessage(45.seconds, TestResult("fixed"))
    stub.recordedSendPrompts should have size 2
    probe.expectTerminated(child, 5.seconds)
  }

  test("failure: invalid JSON on all three read attempts") {
    val ws = tmpWorkspace
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0, 0, 0),
      onSendPrompt = p =>
        val path = extractJsonFilePath(p)
        os.write.over(path, "%%%")
    )
    val probe = testKit.createTestProbe[TestResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[TestResult](
        probe.ref,
        stub,
        "x",
        "y"
      )
    )
    probe.receiveMessage(45.seconds) match
      case LLMActor.LLMError(e) => e shouldBe a[Exception]
      case other                => fail(s"expected LLMError, got $other")
    stub.recordedSendPrompts should have size 3
    probe.expectTerminated(child, 5.seconds)
  }

  test("retry: empty file then valid JSON") {
    val ws = tmpWorkspace
    var n = 0
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0, 0),
      onSendPrompt = p =>
        n += 1
        val path = extractJsonFilePath(p)
        if n == 1 then os.write.over(path, "")
        else os.write.over(path, """{"value":"ok"}""")
    )
    val probe = testKit.createTestProbe[TestResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[TestResult](
        probe.ref,
        stub,
        "x",
        "y"
      )
    )
    probe.expectMessage(45.seconds, TestResult("ok"))
    probe.expectTerminated(child, 5.seconds)
  }

  test("structural: start and sendPrompt carry expected content") {
    val ws = tmpWorkspace
    val outInstr = "OUTPUT_INSTR_UNIQUE"
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0),
      onSendPrompt = p =>
        val path = extractJsonFilePath(p)
        p should include(outInstr)
        p should include(path.toString)
        p should include("value") // field name from TestResult schema
        os.write.over(path, """{"value":"v"}""")
    )
    val probe = testKit.createTestProbe[TestResult | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[TestResult](
        probe.ref,
        stub,
        "INPUT_BODY_UNIQUE",
        outInstr
      )
    )
    probe.expectMessage(30.seconds, TestResult("v"))
    stub.recordedStarts shouldBe Seq(Some("INPUT_BODY_UNIQUE"))
    val p = stub.recordedSendPrompts.head
    p should include("Write the result of your operation to JSON file")
    p should include("following schema")
    p should include(outInstr)
    probe.expectTerminated(child, 5.seconds)
  }

  test("large payload round-trip") {
    val ws = tmpWorkspace
    val stub = new StubAgent(
      ws,
      busyPhases = List(0, 0),
      onSendPrompt = p =>
        val path = extractJsonFilePath(p)
        os.write.over(
          path,
          """{"a":"A","b":1,"c":true,"d":"D","e":2}"""
        )
    )
    val probe = testKit.createTestProbe[LargePayload | LLMActor.LLMError]()
    val child = testKit.spawn(
      LLMActor.start[LargePayload](
        probe.ref,
        stub,
        "big",
        "big out"
      )
    )
    probe.expectMessage(
      30.seconds,
      LargePayload("A", 1, true, "D", 2)
    )
    probe.expectTerminated(child, 5.seconds)
  }

end LLMActorSpec
