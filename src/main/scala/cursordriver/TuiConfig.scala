package cursordriver

/** Injectable poll interval and sleeping for tests (replaces monkeypatching Python's time.sleep /
  * POLL_INTERVAL_S).
  */
final case class TuiConfig(
    pollIntervalS: Double = 1.0,
    sleeper: Double => Unit = d => Thread.sleep(math.max(0L, (d * 1000.0).toLong)),
    clockNanos: () => Long = () => System.nanoTime()
)
