package mill.internal

import mill.api.{Logger, SystemStreams}

import java.io.{InputStream, PrintStream, ByteArrayOutputStream}

class MultiLogger(
    val logger1: Logger,
    val logger2: Logger,
    val inStream0: InputStream
) extends Logger {
  override def toString: String = s"MultiLogger($logger1, $logger2)"
  lazy val streams = new SystemStreams(
    new MultiStream(logger1.streams.out, logger2.streams.out),
    new MultiStream(logger1.streams.err, logger2.streams.err),
    inStream0
  )

  override lazy val unprefixedStreams: SystemStreams = new SystemStreams(
    new MultiStream(logger1.unprefixedStreams.out, logger2.unprefixedStreams.out),
    new MultiStream(logger1.unprefixedStreams.err, logger2.unprefixedStreams.err),
    inStream0
  )

  def info(s: String): Unit = {
    logger1.info(s)
    logger2.info(s)
  }
  def warn(s: String): Unit = {
    logger1.warn(s)
    logger2.warn(s)
  }
  def error(s: String): Unit = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String): Unit = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  override def withPromptLine[T](t: => T): T = {
    logger1.withPromptLine(logger2.withPromptLine(t))
  }

  def prompt: Logger.Prompt = new Logger.Prompt {

    override def logLock[T](block: => T): T = logger1.prompt.logLock{
      logger2.prompt.logLock{
        block
      }
    }
    override def setPromptDetail(key: Seq[String], s: String): Unit = {
      logger1.prompt.setPromptDetail(key, s)
      logger2.prompt.setPromptDetail(key, s)
    }

    override def setPromptLine(
        key: Seq[String],
        keySuffix: String,
        message: String
    ): Unit = {
      logger1.prompt.setPromptLine(key, keySuffix, message)
      logger2.prompt.setPromptLine(key, keySuffix, message)
    }

    override def logPrefixedLine(
        key: Seq[String],
        logMsg: ByteArrayOutputStream,
        logToOut: Boolean
    ): Unit = {
      logger1.prompt.logPrefixedLine(key, logMsg, logToOut)
      logger2.prompt.logPrefixedLine(key, logMsg, logToOut)
    }

    override def clearPromptStatuses(): Unit = {
      logger1.prompt.clearPromptStatuses()
      logger2.prompt.clearPromptStatuses()
    }

    override def removePromptLine(key: Seq[String], message: String): Unit = {
      logger1.prompt.removePromptLine(key, message)
      logger2.prompt.removePromptLine(key, message)
    }

    override def setPromptHeaderPrefix(s: String): Unit = {
      logger1.prompt.setPromptHeaderPrefix(s)
      logger2.prompt.setPromptHeaderPrefix(s)
    }

    override def withPromptPaused[T](t: => T): T = {
      logger1.prompt.withPromptPaused(logger2.prompt.withPromptPaused(t))
    }

    override def withPromptUnpaused[T](t: => T): T = {
      logger1.prompt.withPromptUnpaused(logger2.prompt.withPromptUnpaused(t))
    }

    override def enableTicker: Boolean = logger1.prompt.enableTicker || logger2.prompt.enableTicker

    override def debugEnabled: Boolean = logger1.prompt.debugEnabled || logger2.prompt.debugEnabled

    override def infoColor(s: String): String =
      logger1.prompt.infoColor(logger2.prompt.infoColor(s))
    override def warnColor(s: String): String =
      logger1.prompt.warnColor(logger2.prompt.warnColor(s))
    override def errorColor(s: String): String =
      logger1.prompt.errorColor(logger2.prompt.errorColor(s))
    override def colored: Boolean = logger1.prompt.colored || logger2.prompt.colored

    override def beginChromeProfileEntry(text: String): Unit = {
      logger1.prompt.beginChromeProfileEntry(text)
      logger2.prompt.beginChromeProfileEntry(text)
    }

    override def endChromeProfileEntry(): Unit = {
      logger1.prompt.endChromeProfileEntry()
      logger2.prompt.endChromeProfileEntry()
    }

    override def logBeginChromeProfileEntry(message: String, nanoTime: Long) = {
      logger1.prompt.logBeginChromeProfileEntry(message, nanoTime)
      logger2.prompt.logBeginChromeProfileEntry(message, nanoTime)
    }

    override def logEndChromeProfileEntry(nanoTime: Long) = {
      logger1.prompt.logEndChromeProfileEntry(nanoTime)
      logger2.prompt.logEndChromeProfileEntry(nanoTime)
    }
  }
  def debug(s: String): Unit = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def logKey = logger1.logKey ++ logger2.logKey

  override def redirectOutToErr: Boolean = logger1.redirectOutToErr || logger1.redirectOutToErr
  override def withRedirectOutToErr() = new MultiLogger(
    logger1.withRedirectOutToErr(),
    logger2.withRedirectOutToErr(),
    inStream0
  )
  override def withOutStream(outStream: PrintStream): Logger = {
    new MultiLogger(
      logger1.withOutStream(outStream),
      logger2.withOutStream(outStream),
      inStream0
    )
  }
}
