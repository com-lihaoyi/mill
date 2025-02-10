package mill.internal

import fansi.Attrs
import mill.api.{ColorLogger, Logger, SystemStreams}

import java.io.{InputStream, OutputStream, PrintStream}

private[mill] class MultiLogger(
    val colored: Boolean,
    val logger1: Logger,
    val logger2: Logger,
    val inStream0: InputStream,
    override val debugEnabled: Boolean
) extends ColorLogger {
  override def toString: String = s"MultiLogger($logger1, $logger2)"
  lazy val systemStreams = new SystemStreams(
    new MultiStream(logger1.systemStreams.out, logger2.systemStreams.out),
    new MultiStream(logger1.systemStreams.err, logger2.systemStreams.err),
    inStream0
  )

  private[mill] override lazy val unprefixedSystemStreams: SystemStreams = new SystemStreams(
    new MultiStream(logger1.unprefixedSystemStreams.out, logger2.unprefixedSystemStreams.out),
    new MultiStream(logger1.unprefixedSystemStreams.err, logger2.unprefixedSystemStreams.err),
    inStream0
  )

  def info(s: String): Unit = {
    logger1.info(s)
    logger2.info(s)
  }
  def error(s: String): Unit = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String): Unit = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  override def setPromptDetail(key: Seq[String], s: String): Unit = {
    logger1.setPromptDetail(key, s)
    logger2.setPromptDetail(key, s)
  }

  private[mill] override def setPromptLine(
      key: Seq[String],
      verboseKeySuffix: String,
      message: String
  ): Unit = {
    logger1.setPromptLine(key, verboseKeySuffix, message)
    logger2.setPromptLine(key, verboseKeySuffix, message)
  }

  private[mill] override def setPromptLine(): Unit = {
    logger1.setPromptLine()
    logger2.setPromptLine()
  }

  def debug(s: String): Unit = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def close(): Unit = {
    logger1.close()
    logger2.close()
  }
  private[mill] override def reportKey(key: Seq[String]): Unit = {
    logger1.reportKey(key)
    logger2.reportKey(key)
  }

  override def rawOutputStream: PrintStream = systemStreams.out

  private[mill] override def removePromptLine(key: Seq[String]): Unit = {
    logger1.removePromptLine(key)
    logger2.removePromptLine(key)
  }
  private[mill] override def removePromptLine(): Unit = {
    logger1.removePromptLine()
    logger2.removePromptLine()
  }
  private[mill] override def setPromptHeaderPrefix(s: String): Unit = {
    logger1.setPromptHeaderPrefix(s)
    logger2.setPromptHeaderPrefix(s)
  }

  private[mill] override def withPromptPaused[T](t: => T): T = {
    logger1.withPromptPaused(logger2.withPromptPaused(t))
  }
  private[mill] override def withPromptUnpaused[T](t: => T): T = {
    logger1.withPromptUnpaused(logger2.withPromptUnpaused(t))
  }

  override def enableTicker: Boolean = logger1.enableTicker || logger2.enableTicker

  private[mill] override def subLogger(path: os.Path, key: String, message: String): Logger = {
    new MultiLogger(
      colored,
      logger1.subLogger(path, key, message),
      logger2.subLogger(path, key, message),
      inStream0,
      debugEnabled
    )
  }

  override def infoColor: Attrs = logger1.infoColor ++ logger2.infoColor
  override def errorColor: Attrs = logger1.errorColor ++ logger2.errorColor
  private[mill] override def logPrefixKey = logger1.logPrefixKey ++ logger2.logPrefixKey

  override def withOutStream(outStream: PrintStream): ColorLogger = {
    new MultiLogger(
      colored,
      logger1.withOutStream(outStream),
      logger2.withOutStream(outStream),
      inStream0,
      debugEnabled
    )
  }
}

