package agents4s.tmux

import java.io.PrintStream

/** Dependency injection and polling for tmux-based agents. */
final case class AgentConfig(
    pollIntervalS: Double = 1.0,
    sleeper: Double => Unit = d => Thread.sleep(math.max(0L, (d * 1000.0).toLong)),
    clockNanos: () => Long = () => System.nanoTime(),
    whichExecutable: String => Option[String] = Paths.which,
    /** Sleep after sendKeys text before Enter (Python: 0.2s). */
    postSendKeysPause: Double => Unit = d => Thread.sleep(math.max(0L, (d * 1000.0).toLong)),
    newTmuxServer: String => TmuxServer = s => new TmuxServer(s),
    newPane: (String, String) => Pane = (s, t) => new TmuxPane(s, t),
    /** When false, [[TmuxAgent.stop]] skips tmux kill (for tests with mock panes). */
    killRemoteOnStop: Boolean = true,
    quiet: Boolean = false,
    out: PrintStream = System.out,
    err: PrintStream = System.err
)
