package agents4s.tmux

/** Minimal pane surface for TUI capture and key injection (tmux or test double). */
trait Pane:

  /** Last lines of visible/scrollback region; [start] matches tmux capture-pane -S semantics. */
  def capturePane(start: Int = -10): Seq[String]

  /** Full pane history (tmux: capture-pane -S - -E -). Default: same as [[capturePane]]. */
  def captureEntireScrollback(): Seq[String] = capturePane(start = -10)

  def sendKeys(keys: String, enter: Boolean = false): Unit

  /** Send SIGINT-style interrupt (tmux: send-keys C-c). */
  def sendInterrupt(): Unit

end Pane
