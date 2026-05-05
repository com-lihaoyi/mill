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

    // BSP stderr lines aren't prefixed with the lock-owning task's name, so
    // PromptWaitReporter must keep the label in the wait message.
    override def waitMessageNeedsLabel = true

    // Surface only the lock-wait subset of prompt-line/detail updates into
    // BSP-server stderr — `PromptWaitReporter` routes its "blocked on …"
    // messages through the prompt API even though BSP has no live prompt UI,
    // and without this hook BSP-side lock waits would be invisible to users
    // and tests. Other prompt-line activity (e.g. "resolve foo.bar" status
    // emitted by EvaluatorImpl) is just running-status decoration that would
    // be log spam in BSP, so we leave it as a chrome-profile trace only.
    private def isLockWait(message: String): Boolean = message.startsWith("blocked on ")

    private def emitPromptLine(key: Seq[String], message: String): Unit =
      if (isLockWait(message))
        unprefixedStreams.err.write((Logger.formatPrefix(key) + message + "\n").getBytes)

    override private[mill] def setPromptLine(
        key: Seq[String],
        keySuffix: String,
        message: String
    ): Unit = {
      if (message != "") {
        beginChromeProfileEntry(message)
        emitPromptLine(key, message)
      }
    }

    override private[mill] def setPromptDetail(key: Seq[String], message: String): Unit =
      emitPromptLine(key, message)

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
