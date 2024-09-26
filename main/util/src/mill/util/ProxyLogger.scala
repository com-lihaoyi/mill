package mill.util

import mill.api.Logger

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
  def ticker(key: String, s: String): Unit = logger.ticker(key, s)
  private[mill] override def promptLine(key: String, identSuffix: String, message: String): Unit =
    logger.promptLine(key, identSuffix, message)
  def debug(s: String): Unit = logger.debug(s)

  override def debugEnabled: Boolean = logger.debugEnabled

  override def close(): Unit = logger.close()
  private[mill] override def reportPrefix(s: String): Unit = logger.reportPrefix(s)

  override def rawOutputStream: PrintStream = logger.rawOutputStream
  private[mill] override def endTicker(key: String): Unit = logger.endTicker(key)
  private[mill] override def globalTicker(s: String): Unit = logger.globalTicker(s)
  override def withPromptPaused[T](t: => T): T = logger.withPromptPaused(t)

  override def enableTicker = logger.enableTicker
}
