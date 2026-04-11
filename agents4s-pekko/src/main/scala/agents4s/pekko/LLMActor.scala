package agents4s.pekko

import scala.concurrent.duration.DurationInt

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
"""

  def start[O: JsonSchema: ReadWriter](
      replyTo: ActorRef[O | LLMError],
      agent: Agent,
      inputPrompt: String,
      outputInstructions: String
  ): Behavior[HeartbeatTick.type] =
    Behaviors.withTimers: timers =>
      agent.start(inputPrompt)
      timers.startTimerWithFixedDelay(HeartbeatTimerKey, HeartbeatTick, 1.second)
      awaitDone[O](replyTo, agent, outputInstructions)

  private def promptToWriteResult[O: JsonSchema: ReadWriter](
      agent: Agent,
      outputInstructions: String
  ): os.Path =
    val schema = upickle.default.schema[O].toString
    val resultFilePath = os.temp()
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
      resultFilePath: os.Path,
      outputInstructions: String,
      attemptNo: Int = 1,
      maxAttempts: Int = 3
  ): Behavior[HeartbeatTick.type] =
    Behaviors.receiveMessage: _ =>
      if agent.isBusy then Behaviors.same
      else
        try
          val fileContents = os.read(resultFilePath)
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
