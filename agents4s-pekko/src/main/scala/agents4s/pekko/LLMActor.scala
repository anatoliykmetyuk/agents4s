package agents4s.pekko

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.duration.*

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import upickle.jsonschema.JsonSchema
import upickle.default.*
import upickle.jsonschema.*

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

Do not do anything else - immediately output the result based on your earlier work.
"""

  def start[O: JsonSchema: ReadWriter](
      replyTo: ActorRef[O | LLMError],
      agent: Agent,
      inputPrompt: String,
      outputInstructions: String
  ): Behavior[HeartbeatTick.type] =
    Behaviors.withTimers: timers =>
      agent.start()
      agent.awaitIdle(100.seconds, pollInterval = 100.millis)
      agent.sendPrompt(inputPrompt, promptAsFile = true)
      timers.startTimerWithFixedDelay(HeartbeatTimerKey, HeartbeatTick, 1.second)
      awaitDone[O](replyTo, agent, outputInstructions)

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

  private def awaitDone[O: JsonSchema: ReadWriter](
      replyTo: ActorRef[O | LLMError],
      agent: Agent,
      outputInstructions: String
  ): Behavior[HeartbeatTick.type] =
    Behaviors.receiveMessage: _ =>
      if agent.isBusy then Behaviors.same
      else
        val filepath = promptToWriteResult[O](agent, outputInstructions)
        awaitResultWritten[O](replyTo, agent, filepath, outputInstructions)

  private def awaitResultWritten[O: JsonSchema: ReadWriter](
      replyTo: ActorRef[O | LLMError],
      agent: Agent,
      resultFilePath: java.nio.file.Path,
      outputInstructions: String,
      attemptNo: Int = 1,
      maxAttempts: Int = 3
  ): Behavior[HeartbeatTick.type] =
    Behaviors.receiveMessage: _ =>
      if agent.isBusy then Behaviors.same
      else
        try
          val fileContents = Files.readString(resultFilePath, StandardCharsets.UTF_8)
          val deserialized = upickle.default.read[O](fileContents)
          replyTo ! deserialized
          Behaviors.stopped
        catch
          case e: Exception =>
            if attemptNo >= maxAttempts then
              replyTo ! LLMError(e)
              Behaviors.stopped
            else
              val filepath = promptToWriteResult[O](agent, outputInstructions)
              awaitResultWritten[O](
                replyTo,
                agent,
                filepath,
                outputInstructions,
                attemptNo = attemptNo + 1,
                maxAttempts
              )
