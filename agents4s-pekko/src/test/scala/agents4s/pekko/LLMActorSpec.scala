package agents4s.pekko

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import upickle.default.*
import upickle.jsonschema.*

class LLMActorSpec extends AnyFunSuite with Matchers with ParallelTestExecution:

  import scala.concurrent.duration.*

  private def withTestKit[T](f: ActorTestKit => T): T =
    val kit = ActorTestKit()
    try f(kit)
    finally kit.shutdownTestKit()

  private def tmpWorkspace: java.nio.file.Path =
    Files.createTempDirectory("llm-actor-spec")

  private val jsonPathPattern: Pattern =
    Pattern.compile("following path:\\s*(.+)\\s*", Pattern.MULTILINE)

  def extractJsonFilePath(prompt: String): java.nio.file.Path =
    val m = jsonPathPattern.matcher(prompt)
    m.find() shouldBe true
    java.nio.file.Path.of(m.group(1).trim)

  /** LLMActor sends the task prompt first, then JSON result prompts; only the latter contain a
    * path.
    */
  private def whenJsonResult(p: String)(f: java.nio.file.Path => Unit): Unit =
    if p.contains("following path:") then f(extractJsonFilePath(p))

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
    withTestKit { testKit =>
      val ws = tmpWorkspace
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0), // input sendPrompt, then JSON result sendPrompt
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            Files.writeString(path, """{"value":"hello"}""", StandardCharsets.UTF_8)
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
  }

  test("happy path: busy ticks before result prompt, then busy before read") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 2, 1), // idle after input; busy before/after JSON result
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            Files.writeString(path, """{"value":"delayed"}""", StandardCharsets.UTF_8)
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
  }

  test("complex nested output type") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0), // input, JSON result
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            Files.writeString(path, """{"outer":{"value":"in"},"n":42}""", StandardCharsets.UTF_8)
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
  }

  test("retry: invalid JSON then valid on second attempt") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      var attempt = 0
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0, 0, 0), // input + two JSON result attempts
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            attempt += 1
            if attempt == 1 then Files.writeString(path, "not json {{{", StandardCharsets.UTF_8)
            else Files.writeString(path, """{"value":"fixed"}""", StandardCharsets.UTF_8)
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
      stub.recordedSendPrompts should have size 3
      probe.expectTerminated(child, 5.seconds)
    }
  }

  test("failure: invalid JSON on all three read attempts") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0, 0, 0, 0), // input + three JSON retries
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            Files.writeString(path, "%%%", StandardCharsets.UTF_8)
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
      stub.recordedSendPrompts should have size 4
      probe.expectTerminated(child, 5.seconds)
    }
  }

  test("retry: empty file then valid JSON") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      var n = 0
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0, 0, 0), // input + two JSON attempts
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            n += 1
            if n == 1 then Files.writeString(path, "", StandardCharsets.UTF_8)
            else Files.writeString(path, """{"value":"ok"}""", StandardCharsets.UTF_8)
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
  }

  test("structural: start and sendPrompt carry expected content") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      val outInstr = "OUTPUT_INSTR_UNIQUE"
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0), // task prompt, JSON result prompt
        onSendPrompt = p =>
          if p.contains("following path:") then
            val path = extractJsonFilePath(p)
            p should include(outInstr)
            p should include(path.toString)
            p should include("value") // field name from TestResult schema
            Files.writeString(path, """{"value":"v"}""", StandardCharsets.UTF_8)
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
      stub.recordedStartCalls shouldBe 1
      stub.recordedSendPrompts.head should include("INPUT_BODY_UNIQUE")
      val p = stub.recordedSendPrompts(1)
      p should include("Write the result of your operation to JSON file")
      p should include("following schema")
      p should include(outInstr)
      probe.expectTerminated(child, 5.seconds)
    }
  }

  test("large payload round-trip") {
    withTestKit { testKit =>
      val ws = tmpWorkspace
      val stub = new StubAgent(
        ws,
        busyPhases = List(0, 0),
        onSendPrompt = p =>
          whenJsonResult(p): path =>
            Files.writeString(
              path,
              """{"a":"A","b":1,"c":true,"d":"D","e":2}""",
              StandardCharsets.UTF_8
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
  }

end LLMActorSpec
