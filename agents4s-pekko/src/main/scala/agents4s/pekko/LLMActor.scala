package agents4s.pekko

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import upickle.jsonschema.JsonSchema
import upickle.default.*
import upickle.jsonschema.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import agents4s.Agent
import agents4s.prompt.PromptTemplate

/** An ActorAgent wraps an LLM agent that works on a provided promp. When the work is finished, the
  * Actor will ensure it is converted to the desired type O and sent back to the requesting actor.
  */
object LLMActor:
  val HeartbeatTimerKey = "heartbeat"
  case object HeartbeatTick
  case class LLMError(e: Exception)

  /** Inlined so result prompting works without classpath resources. */
  private[pekko] val ResultPromptTemplate: String =
    """{{OUTPUT_INSTRUCTIONS}}

Write the result of your operation to JSON file at the following path: {{JSON_FILE_PATH}}

Use the following schema to record your result: {{JSON_SCHEMA}}

Output in the precise format specified above.
"""

  def start[O: JsonSchema: ReadWriter](
      replyTo: ActorRef[O | LLMError],
      agent: Agent,
      inputPrompt: String,
      outputInstructions: String
  ): Behavior[HeartbeatTick.type] =
    Behaviors.withTimers: timers =>
      agent.start()
      timers.startTimerWithFixedDelay(HeartbeatTimerKey, HeartbeatTick, 1.second)
      runLoop[O](replyTo, agent, inputPrompt, outputInstructions)

  private def promptToWriteResult[O: JsonSchema: ReadWriter](
      agent: Agent,
      outputInstructions: String
  ): java.nio.file.Path =
    val schema = upickle.default.schema[O].toString
    val resultFilePath = Files.createTempFile("agents4s-llm-result-", ".json")
    val prompt = PromptTemplate.substitute(
      ResultPromptTemplate,
      Map(
        "OUTPUT_INSTRUCTIONS" -> outputInstructions,
        "JSON_FILE_PATH" -> resultFilePath.toString,
        "JSON_SCHEMA" -> schema
      )
    )
    agent.sendPrompt(prompt, promptAsFile = true)
    resultFilePath

  /** Heartbeat loop: send input once the agent is idle, then drive JSON result read / retries. */
  private def runLoop[O: JsonSchema: ReadWriter](
      replyTo: ActorRef[O | LLMError],
      agent: Agent,
      inputPrompt: String,
      outputInstructions: String
  ): Behavior[HeartbeatTick.type] =
    var inputSent = false
    var resultPathOpt: Option[java.nio.file.Path] = None
    var attemptNo = 1
    val maxAttempts = 3
    /** Avoid reading the result file while the TUI still shows a stale idle tail (non-blocking [[Agent.sendPrompt]]). */
    var sawBusySinceResultPrompt = false
    var resultPromptStartNanos: Long = 0L
    /** When [[Agent.isBusy]] is never true (e.g. stubs), still advance so we do not spin forever. */
    val busyFallbackAfter: FiniteDuration = 2.seconds

    def beginResultAttempt(path: java.nio.file.Path, resetAttemptCounter: Boolean): Unit =
      resultPathOpt = Some(path)
      if resetAttemptCounter then attemptNo = 1
      sawBusySinceResultPrompt = false
      resultPromptStartNanos = System.nanoTime()

    def step: Behavior[HeartbeatTick.type] =
      Behaviors.receiveMessage: _ =>
        if !inputSent then
          if agent.isBusy then step
          else
            agent.sendPrompt(inputPrompt, promptAsFile = true)
            inputSent = true
            step
        else
          resultPathOpt match
            case None =>
              if agent.isBusy then step
              else
                beginResultAttempt(
                  promptToWriteResult[O](agent, outputInstructions),
                  resetAttemptCounter = true
                )
                step
            case Some(path) =>
              val busyFallbackDue =
                System.nanoTime() - resultPromptStartNanos > busyFallbackAfter.toNanos
              if !sawBusySinceResultPrompt then
                if agent.isBusy || busyFallbackDue then sawBusySinceResultPrompt = true
                step
              else if agent.isBusy then step
              else
                try
                  val fileContents = Files.readString(path, StandardCharsets.UTF_8)
                  val deserialized = upickle.default.read[O](fileContents)
                  replyTo ! deserialized
                  Behaviors.stopped
                catch
                  case e: Exception =>
                    if attemptNo >= maxAttempts then
                      replyTo ! LLMError(e)
                      Behaviors.stopped
                    else
                      attemptNo += 1
                      beginResultAttempt(
                        promptToWriteResult[O](agent, outputInstructions),
                        resetAttemptCounter = false
                      )
                      step
    step
