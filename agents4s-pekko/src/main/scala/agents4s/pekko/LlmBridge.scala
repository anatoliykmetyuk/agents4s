package agents4s.pekko

import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import agents4s.tmux.TmuxAgent

private[agents4s] enum LlmBridgeStep:
  case SendWork, PollWork, SendFollowUp, PollFollowUp

/** Internal heartbeat message; do not send from harness code. */
case object LlmBridgeTick

/** Timer key for [[LlmBridge]]; library use only. */
case object LlmBridgeTickTimerKey

/** Heartbeat-driven [[TmuxAgent]]. Mail is [[In]] (and internal [[LlmBridgeTick]] only). Completed
  * work goes to `replyTo` passed into [[behavior]].
  *
  * Pekko Typed has no `sender()` — pass the destination when you spawn, e.g.
  * `context.spawn(bridge.behavior(self), "llm")`.
  */
abstract class LlmBridge[In, Out, A <: TmuxAgent]:

  def createAgent(input: In): A
  def buildPrompt(input: In): String
  def outputPath(input: In): os.Path
  def parseOutput(raw: String): Out
  def heartbeatInterval: FiniteDuration =
    import scala.concurrent.duration.DurationInt
    250.millis
  def followUpPrompt(input: In, path: os.Path): String =
    s"""Write only valid JSON to `$path` with no surrounding prose or markdown."""

  final def behavior(replyTo: ActorRef[Out]): Behavior[In | LlmBridgeTick.type] =
    Behaviors.setup { _ =>
      Behaviors.withTimers { timers =>
        var job: Option[(A, In)] = None
        var step: LlmBridgeStep = LlmBridgeStep.SendWork
        var followUpText: String = ""

        def tick(): Unit =
          timers.startSingleTimer(
            LlmBridgeTickTimerKey,
            LlmBridgeTick: In | LlmBridgeTick.type,
            heartbeatInterval
          )

        Behaviors.receiveMessage:
          case LlmBridgeTick =>
            job.foreach { case (agent, input) =>
              step match
                case LlmBridgeStep.SendWork =>
                  if agent.isReady then
                    agent.firePrompt(buildPrompt(input))
                    step = LlmBridgeStep.PollWork
                  tick()
                case LlmBridgeStep.PollWork =>
                  if agent.isBusy then tick()
                  else
                    followUpText = followUpPrompt(input, outputPath(input))
                    step = LlmBridgeStep.SendFollowUp
                    tick()
                case LlmBridgeStep.SendFollowUp =>
                  if agent.isReady then
                    agent.firePrompt(followUpText, promptAsFile = false)
                    step = LlmBridgeStep.PollFollowUp
                  tick()
                case LlmBridgeStep.PollFollowUp =>
                  if agent.isBusy then tick()
                  else
                    replyTo ! parseOutput(os.read(outputPath(input)))
                    agent.stop()
                    timers.cancel(LlmBridgeTickTimerKey)
                    job = None
                    step = LlmBridgeStep.SendWork
            }
            Behaviors.same

          case input: In @unchecked =>
            if job.isEmpty then
              val agent = createAgent(input)
              if agent.start(None) == 0 then
                job = Some((agent, input))
                step = LlmBridgeStep.SendWork
                tick()
              else agent.stop()
            Behaviors.same
      }
    }

end LlmBridge
