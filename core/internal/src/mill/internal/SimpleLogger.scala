package mill.internal

import mill.api.{SystemStreams, Logger}

import java.io.PrintStream

private[mill] class SimpleLogger(
    override val unprefixedStreams: SystemStreams,
    override val logKey: Seq[String],
    debugEnabled: Boolean
) extends Logger {
  override def toString: String = s"SimpleLogger($unprefixedStreams, $debugEnabled)"

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

  def info(s: String): Unit =
    log(s)

  def warn(s: String): Unit =
    log(s)

  def error(s: String): Unit =
    log(s)

  private var currentKey = logKey

  private def log(s: String): Unit = {
    val prefix0 = prefix(currentKey)
    for (line <- s.linesWithSeparators) {
      unprefixedStreams.err.print(prefix0)
      unprefixedStreams.err.print(line)
    }
    unprefixedStreams.err.println()
  }

  val prompt = new Logger.Prompt.NoOp {
    override def enableTicker = true
    override def reportKey(key: Seq[String]): Unit =
      currentKey = key
  }
  def ticker(s: String): Unit = ()

  def debug(s: String): Unit =
    if (debugEnabled)
      log(s)
}
