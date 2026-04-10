package cursordriver

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.UUID
import java.util.regex.Pattern

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

  private def tmpWorkspace: os.Path =
    os.Path(Files.createTempDirectory("cursor4s-int").toFile)

  private def uniqueSessionIds: (String, String) =
    val u = UUID.randomUUID().toString.replace("-", "").take(12)
    (s"cd-test-$u", s"cd-$u")

  private def withTimeout[T](body: => T): T =
    failAfter(Span(900, org.scalatest.time.Seconds))(body)

  test("I1 cold start await ready") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false,
      tuiConfig = TuiConfig()
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.pane.nonEmpty shouldBe true
        agent.awaitReady(timeoutS = 900)
        agent.isReady shouldBe true
        agent.isTrustPrompt shouldBe false
    finally agent.stop()
  }

  test("I2 optional trust phase recorded") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.pane.nonEmpty shouldBe true
        val _ = agent.isTrustPrompt
        agent.awaitReady(timeoutS = 900)
        agent.isReady shouldBe true
    finally agent.stop()
  }

  test("I3 start with prompt one shot") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val proof = tmp / "proof_integration.txt"
    val instruction =
      s"""Create a file at exactly this path with UTF-8 content OK:\\n$proof\\nReply with a single word DONE when finished."""
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = true
    )
    withTimeout:
      val rc = agent.start(Some(instruction))
      rc shouldBe 0
      if os.isFile(proof) then
        val text = os.read(proof).strip()
        (text.contains("OK") || text == "OK") shouldBe true
  }

  for (turns <- Seq(1, 2))
    test(s"multi turn ready busy done (turns=$turns)") {
      gate()
      val tmp = tmpWorkspace
      val (soc, label) = uniqueSessionIds
      val log = tmp / "turn_log.txt"
      val agent = new CursorAgent(
        tmp,
        model,
        tmuxSocket = soc,
        label = label,
        quiet = true,
        killSession = false
      )
      try
        withTimeout:
          agent.start(None) shouldBe 0
          agent.awaitReady(timeoutS = 900)
          for (t <- 0 until turns)
            agent.isReady shouldBe true
            val token = UUID.randomUUID().toString.replace("-", "").take(8)
            agent.sendPrompt(
              s"Append exactly one line to $log: TURN $t $token\\nThen stop. Reply DONE.",
              timeoutS = 900
            )
            agent.awaitDone(timeoutS = 900)
            agent.awaitReady(timeoutS = 900)
            agent.isReady shouldBe true
            if os.isFile(log) then
              val content = os.read(log)
              content should include(token)
      finally agent.stop()
    }

  test("K1 kill session true session removed") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = true
    )
    withTimeout:
      agent.start(None) shouldBe 0
      val server = new TmuxServer(soc)
      server.hasSession(label) shouldBe false
  }

  test("K2 kill session false session survives") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        val server = new TmuxServer(soc)
        server.hasSession(label) shouldBe true
    finally agent.stop()
  }

  test("K3 stop kills session when kill session false") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    withTimeout:
      agent.start(None) shouldBe 0
      agent.pane.nonEmpty shouldBe true
      agent.stop()
      agent.pane shouldBe empty
      val server = new TmuxServer(soc)
      server.hasSession(label) shouldBe false
  }

  test("K4 stop sends Ctrl+C when agent is busy then kills session") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.awaitReady(timeoutS = 900)
        agent.sendPrompt(
          "Count from 1 to 5000 in your output, one number per line. Do not stop or summarize early.",
          timeoutS = 900,
          promptAsFile = false
        )
        Thread.sleep(500)
        assume(agent.isBusy, "agent finished too fast for busy-stop integration test")
        agent.stop()
        agent.pane shouldBe empty
        val server = new TmuxServer(soc)
        server.hasSession(label) shouldBe false
    finally
      if agent.pane.nonEmpty then agent.stop()
  }

  test("send prompt as file single followup") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val token = UUID.randomUUID().toString.replace("-", "").take(12)
    val proof = tmp / s"proof_file_mode_$token.txt"
    val instruction =
      s"""Create a UTF-8 file at exactly this path with content: OK-$token\\n$proof\\nReply DONE when finished."""
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.awaitReady(timeoutS = 900)
        agent.sendPrompt(instruction, timeoutS = 900, promptAsFile = true)
        agent.awaitDone(timeoutS = 900)
        agent.awaitReady(timeoutS = 900)
    finally agent.stop()

    os.isFile(proof) shouldBe true
    val text = os.read(proof).strip()
    (text.contains(s"OK-$token") || text == s"OK-$token") shouldBe true
  }

  test("send prompt as file multi chunk") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val log = tmp / "chunk_log.txt"
    val tokens = (1 to 3).map(_ => UUID.randomUUID().toString.replace("-", "").take(10)).toSeq
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.awaitReady(timeoutS = 900)
        tokens.zipWithIndex.foreach { case (tok, i) =>
          agent.sendPrompt(
            s"Append exactly one line to $log: CHUNK$i $tok\\nThen stop. Reply DONE.",
            timeoutS = 900,
            promptAsFile = true
          )
          agent.awaitDone(timeoutS = 900)
          agent.awaitReady(timeoutS = 900)
        }
    finally agent.stop()

    os.isFile(log) shouldBe true
    val body = os.read(log)
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
    val a = tmp / s"long_a_$u.txt"
    val b = tmp / s"long_b_$u.txt"
    val c = tmp / s"long_c_$u.txt"
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
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.awaitReady(timeoutS = 900)
        agent.sendPrompt(instruction, timeoutS = 900, promptAsFile = true)
        agent.awaitDone(timeoutS = 900)
    finally agent.stop()

    os.read(a).strip() shouldBe s"ALPHA-$u"
    os.read(b).strip() shouldBe s"BETA-$u"
    os.read(c).strip() shouldBe s"GAMMA-$u"
  }

  test("start with prompt then file followup") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val secret = UUID.randomUUID().toString.replace("-", "").take(12)
    val round1 = tmp / "round1.txt"
    val round2 = tmp / "round2.txt"
    val firstPrompt =
      s"""Create a UTF-8 file at $round1 with exactly two lines:\\nLINE1:$secret\\nLINE2:IGNORE\\nReply DONE when finished."""
    val follow =
      s"""Read the file at $round1. Create $round2 with a single line that is the LINE1 value from round1 repeated twice with no separator (e.g. if LINE1 is abc then write abcabc). Reply DONE."""
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(Some(firstPrompt)) shouldBe 0
        os.isFile(round1) shouldBe true
        agent.sendPrompt(follow, timeoutS = 900, promptAsFile = true)
        agent.awaitDone(timeoutS = 900)
    finally agent.stop()

    os.isFile(round2) shouldBe true
    os.read(round2).strip() shouldBe secret * 2
  }

  test("prompt as file temp cleanup on stop") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val log = tmp / "cleanup_log.txt"
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.awaitReady(timeoutS = 900)
        (1 to 2).foreach { _ =>
          val tok = UUID.randomUUID().toString.replace("-", "").take(8)
          agent.sendPrompt(
            s"Append one line to $log: $tok\\nThen stop. Reply DONE.",
            timeoutS = 900,
            promptAsFile = true
          )
          agent.awaitDone(timeoutS = 900)
          agent.awaitReady(timeoutS = 900)
        }
        globPromptMd(tmp).nonEmpty shouldBe true
    finally agent.stop()

    globPromptMd(tmp) shouldBe empty
  }

  for (promptAsFile <- Seq(true, false))
    test(s"send prompt direct vs file same result (promptAsFile=$promptAsFile)") {
      gate()
      val tmp = tmpWorkspace
      val (soc, label) = uniqueSessionIds
      val token = UUID.randomUUID().toString.replace("-", "").take(12)
      val proof = tmp / s"parity_${promptAsFile}_$token.txt"
      val instruction =
        s"""Create a UTF-8 file at $proof with content exactly: TOKEN=$token\\nReply DONE when finished."""
      val agent = new CursorAgent(
        tmp,
        model,
        tmuxSocket = soc,
        label = label,
        quiet = true,
        killSession = false
      )
      try
        withTimeout:
          agent.start(None) shouldBe 0
          agent.awaitReady(timeoutS = 900)
          agent.sendPrompt(instruction, timeoutS = 900, promptAsFile = promptAsFile)
          agent.awaitDone(timeoutS = 900)
      finally agent.stop()

      os.isFile(proof) shouldBe true
      os.read(proof) should include(token)
    }

  test("quiet suppresses driver prints") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val baos = new ByteArrayOutputStream()
    val out = new PrintStream(baos, true, "UTF-8")
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = true,
      out = out
    )
    withTimeout:
      val rc = agent.start(None)
      rc shouldBe 0
    val s = baos.toString("UTF-8")
    s should not include ("starting agent")
    s should not include ("attach with")
  }

  test("no source activate after prompt") {
    gate()
    val tmp = tmpWorkspace
    val (soc, label) = uniqueSessionIds
    val proof = tmp / "proof.txt"
    val instruction =
      s"""Create a file at exactly this path with content OK:\\n$proof\\nReply DONE when finished."""
    val agent = new CursorAgent(
      tmp,
      model,
      tmuxSocket = soc,
      label = label,
      quiet = true,
      killSession = false
    )
    try
      withTimeout:
        agent.start(None) shouldBe 0
        agent.pane.nonEmpty shouldBe true
        agent.awaitReady(timeoutS = 900)
        agent.sendPrompt(instruction, timeoutS = 900, promptAsFile = true)
        agent.awaitDone(timeoutS = 900)
        val lines = agent.pane.get.captureEntireScrollback()
        val dump = TuiOps.stripAnsi(lines.mkString("\n"))
        val p = Pattern.compile("(?i)source\\s+\\S*activate")
        val m = p.matcher(dump)
        val hits = Iterator.continually(m.find()).takeWhile(identity).length
        withClue(s"pane contains venv activate command\n$dump")(hits shouldBe 0)
    finally agent.stop()
  }

  private def globPromptMd(root: os.Path): Seq[os.Path] =
    val d = root / ".cursor" / "prompts"
    if os.isDir(d) then
      os.list(d)
        .filter { p =>
          val n = p.last
          n.startsWith("cursor4s-prompt-") && n.endsWith(".md")
        }
        .toSeq
    else Seq.empty

end CursorAgentIntegrationTest
