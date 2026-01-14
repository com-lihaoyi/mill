package mill.internal

import mill.api.{SystemStreams, Logger}

class BspLogger(
    override val unprefixedStreams: SystemStreams,
    override val logKey: Seq[String],
    debugEnabled: Boolean,
    chromeProfileLogger: JsonArrayLogger.ChromeProfile
) extends Logger.Upstream {
  override def toString: String = s"SimpleLogger($unprefixedStreams, $debugEnabled)"

  override def redirectOutToErr: Boolean = false

  private val threadNumberer = new ThreadNumberer()

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

    override private[mill] def setPromptLine(
        key: Seq[String],
        keySuffix: String,
        message: String
    ): Unit = {
      if (message != "") beginChromeProfileEntry(message)
    }

    override private[mill] def removePromptLine(key: Seq[String], message: String): Unit = {
      if (message != "") endChromeProfileEntry()
    }

    override private[mill] def beginChromeProfileEntry(text: String): Unit = {
      chromeProfileLogger.logBegin(
        text,
        "job",
        System.nanoTime() / 1000,
        threadNumberer.getThreadId(Thread.currentThread())
      )
    }

    override private[mill] def endChromeProfileEntry(): Unit = {
      chromeProfileLogger.logEnd(
        System.nanoTime() / 1000,
        threadNumberer.getThreadId(Thread.currentThread())
      )
    }
  }
}
