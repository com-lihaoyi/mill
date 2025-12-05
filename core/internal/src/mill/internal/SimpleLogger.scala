package mill.internal

import mill.api.{SystemStreams, Logger}

import java.io.PrintStream

class SimpleLogger(
    override val unprefixedStreams: SystemStreams,
    override val logKey: Seq[String],
    debugEnabled: Boolean
) extends Logger {
  override def toString: String = s"SimpleLogger($unprefixedStreams, $debugEnabled)"

  private val linePrefix: String = Logger.formatPrefix(logKey)
  private def prefixPrintStream(stream: java.io.OutputStream) = {
    new PrintStream(new LineBufferingOutputStream(s => stream.write((linePrefix + s).getBytes)))
  }

  val streams = new SystemStreams(
    out = prefixPrintStream(unprefixedStreams.out),
    err = prefixPrintStream(unprefixedStreams.err),
    unprefixedStreams.in
  )

  def isInteractive() = false

  def info(s: String): Unit =
    unprefixedStreams.err.println(s)

  def warn(s: String): Unit =
    unprefixedStreams.err.println(s)

  def error(s: String): Unit =
    unprefixedStreams.err.println(s)

  val prompt = new Logger.Prompt.NoOp {
    override def logPrefixedLine(
        key: Seq[String],
        logMsg: java.io.ByteArrayOutputStream,
        logToOut: Boolean
    ): Unit = {
      if (logMsg.size() != 0) {
        val bytes = logMsg
          .toString
          .linesWithSeparators
          .map(Logger.formatPrefix(key).getBytes ++ _.getBytes)
          .toArray
          .flatten

        if (logToOut) unprefixedStreams.out.write(bytes)
        else unprefixedStreams.err.write(bytes)
      }
    }
    override def enableTicker = true
  }
  def ticker(s: String): Unit = ()

  def debug(s: String): Unit =
    if (debugEnabled)
      unprefixedStreams.err.println(s)
}
