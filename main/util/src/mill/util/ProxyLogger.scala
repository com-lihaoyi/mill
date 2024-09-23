package mill.util

import mill.api.Logger

import java.io.PrintStream

/**
 * A Logger that forwards all logging to another Logger.  Intended to be
 * used as a base class for wrappers that modify logging behavior.
 */
class ProxyLogger(logger: Logger) extends Logger {
  override def toString = s"ProxyLogger($logger)"
  def colored = logger.colored

  lazy val systemStreams = logger.systemStreams

  def info(s: String): Unit = logger.info(s)
  def error(s: String): Unit = logger.error(s)
  def ticker(s: String): Unit = logger.ticker(s)
  def debug(s: String): Unit = logger.debug(s)

  override def debugEnabled: Boolean = logger.debugEnabled

  override def close(): Unit = logger.close()

  override def rawOutputStream: PrintStream = logger.rawOutputStream
  override def endTicker(): Unit = logger.endTicker()
  override def globalTicker(s: String): Unit = logger.globalTicker(s)
}
