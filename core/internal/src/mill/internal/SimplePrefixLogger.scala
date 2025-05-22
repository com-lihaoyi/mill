package mill.internal

import mill.api.{SystemStreams, Logger}

import java.io.PrintStream

private[mill] class SimplePrefixLogger(
    override val unprefixedStreams: SystemStreams,
    override val logKey: Seq[String],
    debugEnabled: Boolean
) extends Logger {
  override def toString: String = s"SimplePrefixLogger($unprefixedStreams, $debugEnabled)"

  private val linePrefix: String =
    if (logKey.isEmpty) ""
    else s"[${logKey.mkString("-")}] "
  private def prefixPrintStream(stream: java.io.OutputStream) = {
    new PrintStream(new LinePrefixOutputStream(
      linePrefix,
      stream,
      () => ()
    ))
  }

  val streams = new SystemStreams(
    out = prefixPrintStream(unprefixedStreams.out),
    err = prefixPrintStream(unprefixedStreams.err),
    unprefixedStreams.in
  )

  def isInteractive() = false

  private def prefix(logKey: Seq[String]) =
    if (logKey.isEmpty) ""
    else logKey.mkString("[", "-", "] ")

  def info(logKey: Seq[String], s: String): Unit =
    log(logKey, s)

  def warn(logKey: Seq[String], s: String): Unit =
    log(logKey, s)

  def error(logKey: Seq[String], s: String): Unit =
    log(logKey, s)

  private def log(logKey: Seq[String], s: String): Unit = {
    val prefix0 = prefix(logKey)
    for (line <- s.linesWithSeparators) {
      unprefixedStreams.err.print(prefix0)
      unprefixedStreams.err.print(line)
    }
    unprefixedStreams.err.println()
  }

  val prompt = new Logger.Prompt.NoOp {
    override def enableTicker = true
  }
  def ticker(s: String): Unit = ()

  def debug(logKey: Seq[String], s: String): Unit =
    if (debugEnabled)
      log(logKey, s)
}
