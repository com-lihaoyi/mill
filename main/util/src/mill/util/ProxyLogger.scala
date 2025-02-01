package mill.util

import mill.api.{Logger, SystemStreams}

import java.io.PrintStream

/**
 * A Logger that forwards all logging to another Logger.  Intended to be
 * used as a base class for wrappers that modify logging behavior.
 */
class ProxyLogger(logger: Logger) extends Logger {
  override def toString: String = s"ProxyLogger($logger)"
  def colored = logger.colored

  lazy val systemStreams = logger.systemStreams

  def info(s: String): Unit = logger.info(s)
  def error(s: String): Unit = logger.error(s)
  def ticker(s: String): Unit = logger.ticker(s)
  override def setPromptDetail(key: Seq[String], s: String): Unit = logger.setPromptDetail(key, s)
  private[mill] override def setPromptLine(): Unit = logger.setPromptLine()
  private[mill] override def setPromptLine(
      key: Seq[String],
      verboseKeySuffix: String,
      message: String
  ): Unit =
    logger.setPromptLine(key, verboseKeySuffix, message)
  def debug(s: String): Unit = logger.debug(s)

  override def debugEnabled: Boolean = logger.debugEnabled

  override def close(): Unit = logger.close()
  private[mill] override def reportKey(key: Seq[String]): Unit = logger.reportKey(key)

  override def rawOutputStream: PrintStream = logger.rawOutputStream
  private[mill] override def removePromptLine(key: Seq[String]): Unit = logger.removePromptLine(key)
  private[mill] override def removePromptLine(): Unit = logger.removePromptLine()
  private[mill] override def setPromptHeaderPrefix(s: String): Unit =
    logger.setPromptHeaderPrefix(s)
  private[mill] override def withPromptPaused[T](t: => T): T = logger.withPromptPaused(t)
  private[mill] override def withPromptUnpaused[T](t: => T): T = logger.withPromptUnpaused(t)

  override def enableTicker = logger.enableTicker
  override def infoColor: fansi.Attrs = logger.infoColor
  override def errorColor: fansi.Attrs = logger.errorColor
  private[mill] override def logPrefixKey: Seq[String] = logger.logPrefixKey
  private[mill] override def unprefixedSystemStreams: SystemStreams = logger.unprefixedSystemStreams
}
