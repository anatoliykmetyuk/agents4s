package agents4s.cursor

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.regex.Pattern

import scala.concurrent.duration.*

import agents4s.tmux.{Paths, TmuxServer}

import org.scalatest.Assertions.{assume, withClue}
import org.scalatest.concurrent.TimeLimits
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Span
import org.scalatest.time.SpanSugar.*

class CursorAgentIntegrationTest extends AnyFunSuite with Matchers with TimeLimits:

  private val model: String =
    sys.env.getOrElse("CURSOR_DRIVER_MODEL", "composer-2-fast")

  private def integrationEnvSet: Boolean =
    sys.env.get("CURSOR_DRIVER_INTEGRATION").map(_.trim.toLowerCase).exists(Set("1", "true", "yes"))

  private def gate(): Unit =
    assume(integrationEnvSet, "Set CURSOR_DRIVER_INTEGRATION=1 to run live agent tests")
    assume(Paths.which("agent").nonEmpty, "Cursor `agent` CLI not on PATH")
    assume(Paths.which("tmux").nonEmpty, "tmux not on PATH")

  private def tmpWorkspace: java.nio.file.Path =
    Files.createTempDirectory("agents4s-int")

  private def uniqueSessionIds: (String, String) =
    val u = UUID.randomUUID().toString.replace("-", "").take(12)
    (s"cd-test-$u", s"cd-$u")

  private def withTimeout[T](body: => T): T =
    failAfter(Span(100, org.scalatest.time.Seconds))(body)

  test("I1 cold start await ready") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    try
      withTimeout:
        agent.start()
        agent.isStarted shouldBe true
        agent.awaitIdle(100.seconds)
        agent.isIdle shouldBe true
        agent.isTrustPrompt shouldBe false
    finally agent.stop()
  }

  test("I2 optional trust phase recorded") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    try
      withTimeout:
        agent.start()
        agent.isStarted shouldBe true
        val _ = agent.isTrustPrompt
        agent.awaitIdle(100.seconds)
        agent.isIdle shouldBe true
    finally agent.stop()
  }

  for (turns <- Seq(1, 2))
    test(s"multi turn ready busy done (turns=$turns)") {
      gate()
      val tmp = tmpWorkspace
      val (soc, label) = uniqueSessionIds
      val log = tmp.resolve("turn_log.txt")
      val agent = new CursorAgent(tmp, model, socket = soc, label = label)
      try
        withTimeout:
          agent.start()
          agent.awaitIdle(100.seconds)
          for (t <- 0 until turns)
            agent.isIdle shouldBe true
            val token = UUID.randomUUID().toString.replace("-", "").take(8)
            agent.sendPrompt(
              s"Append exactly one line to $log: TURN $t $token\\nThen stop. Reply DONE.",
              promptAsFile = true
            )
            agent.awaitIdle(100.seconds)
            agent.isIdle shouldBe true
            if Files.isRegularFile(log) then
              val content = Files.readString(log, StandardCharsets.UTF_8)
              content should include(token)
      finally agent.stop()
    }

  test("K3 stop kills session") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    withTimeout:
      agent.start()
      agent.isStarted shouldBe true
      agent.stop()
      agent.isStarted shouldBe false
      val server = new TmuxServer(soc)
      server.hasSession(label) shouldBe false
  }

  test("send prompt as file single followup") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val token = UUID.randomUUID().toString.replace("-", "").take(12)
    val proof = tmp.resolve(s"proof_file_mode_$token.txt")
    val instruction =
      s"""Create a UTF-8 file at exactly this path with content: OK-$token\\n$proof\\nReply DONE when finished."""
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    try
      withTimeout:
        agent.start()
        agent.awaitIdle(100.seconds)
        agent.sendPrompt(instruction, promptAsFile = true)
        agent.awaitIdle(100.seconds)
    finally agent.stop()

    Files.isRegularFile(proof) shouldBe true
    val text = Files.readString(proof, StandardCharsets.UTF_8).strip()
    (text.contains(s"OK-$token") || text == s"OK-$token") shouldBe true
  }

  test("send prompt as file multi chunk") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val log = tmp.resolve("chunk_log.txt")
    val tokens = (1 to 3).map(_ => UUID.randomUUID().toString.replace("-", "").take(10)).toSeq
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    try
      withTimeout:
        agent.start()
        agent.awaitIdle(100.seconds)
        tokens.zipWithIndex.foreach { case (tok, i) =>
          agent.sendPrompt(
            s"Append exactly one line to $log: CHUNK$i $tok\\nThen stop. Reply DONE.",
            promptAsFile = true
          )
          agent.awaitIdle(100.seconds)
        }
    finally agent.stop()

    Files.isRegularFile(log) shouldBe true
    val body = Files.readString(log, StandardCharsets.UTF_8)
    tokens.zipWithIndex.foreach { case (tok, i) =>
      body should include(s"CHUNK$i")
      body should include(tok)
    }
    (0 until tokens.length - 1).foreach { i =>
      body.indexOf(tokens(i)) should be < body.indexOf(tokens(i + 1))
    }
  }

  test("send prompt as file long prompt") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val u = UUID.randomUUID().toString.replace("-", "").take(8)
    val a = tmp.resolve(s"long_a_$u.txt")
    val b = tmp.resolve(s"long_b_$u.txt")
    val c = tmp.resolve(s"long_c_$u.txt")
    val filler = (1 until 29).map(n => s"Context line $n — ignore this paragraph.").mkString("\n")
    val instruction =
      s"""Follow every step below. Reply DONE when all files exist.

$filler

Step 1: Write exactly the text ALPHA-$u (no newline) to file:
$a

Step 2: Write exactly the text BETA-$u (no newline) to file:
$b

Step 3: Write exactly the text GAMMA-$u (no newline) to file:
$c
"""
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    try
      withTimeout:
        agent.start()
        agent.awaitIdle(100.seconds)
        agent.sendPrompt(instruction, promptAsFile = true)
        agent.awaitIdle(100.seconds)
    finally agent.stop()

    Files.readString(a, StandardCharsets.UTF_8).strip() shouldBe s"ALPHA-$u"
    Files.readString(b, StandardCharsets.UTF_8).strip() shouldBe s"BETA-$u"
    Files.readString(c, StandardCharsets.UTF_8).strip() shouldBe s"GAMMA-$u"
  }

  for (promptAsFile <- Seq(true, false))
    test(s"send prompt direct vs file same result (promptAsFile=$promptAsFile)") {
      gate()
      val tmp = tmpWorkspace
      val (soc, label) = uniqueSessionIds
      val token = UUID.randomUUID().toString.replace("-", "").take(12)
      val proof = tmp.resolve(s"parity_${promptAsFile}_$token.txt")
      val instruction =
        s"""Create a UTF-8 file at $proof with content exactly: TOKEN=$token\\nReply DONE when finished."""
      val agent = new CursorAgent(tmp, model, socket = soc, label = label)
      try
        withTimeout:
          agent.start()
          agent.awaitIdle(100.seconds)
          agent.sendPrompt(instruction, promptAsFile = promptAsFile)
          agent.awaitIdle(100.seconds)
      finally agent.stop()

      Files.isRegularFile(proof) shouldBe true
      Files.readString(proof, StandardCharsets.UTF_8) should include(token)
    }

  test("no source activate after prompt") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val proof = tmp.resolve("proof.txt")
    val instruction =
      s"""Create a file at exactly this path with content OK:\\n$proof\\nReply DONE when finished."""
    val agent = new CursorAgent(tmp, model, socket = soc, label = label)
    try
      withTimeout:
        agent.start()
        agent.isStarted shouldBe true
        agent.awaitIdle(100.seconds)
        agent.sendPrompt(instruction, promptAsFile = true)
        agent.awaitIdle(100.seconds)
        val lines = agent.pane.captureEntireScrollback()
        val dump = TmuxServer.stripAnsi(lines.mkString("\n"))
        val p = Pattern.compile("(?i)source\\s+\\S*activate")
        val m = p.matcher(dump)
        val hits = Iterator.continually(m.find()).takeWhile(identity).length
        withClue(s"pane contains venv activate command\n$dump")(hits shouldBe 0)
    finally agent.stop()
  }

end CursorAgentIntegrationTest
