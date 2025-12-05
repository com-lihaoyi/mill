package mill.internal

import mill.api.{SystemStreams, Logger}

import java.io.PrintStream

class BspLogger(
    override val unprefixedStreams: SystemStreams,
    override val logKey: Seq[String],
    debugEnabled: Boolean
) extends Logger.Upstream {
  override def toString: String = s"SimpleLogger($unprefixedStreams, $debugEnabled)"

  override def redirectOutToErr: Boolean = false
  private val linePrefix: String = Logger.formatPrefix(logKey)
  

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
}
