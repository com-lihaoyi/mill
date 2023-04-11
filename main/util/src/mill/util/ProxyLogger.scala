package mill.util

import mill.api.Logger


/**
 * A Logger that forwards all logging to another Logger.  Intended to be
 * used as a base class for wrappers that modify logging behavior.
 */
class ProxyLogger(logger: Logger) extends Logger {
  def colored = logger.colored

  lazy val systemStreams = logger.systemStreams

  def info(s: String) = logger.info(s)
  def error(s: String) = logger.error(s)
  def ticker(s: String) = logger.ticker(s)
  def debug(s: String) = logger.debug(s)

  override def debugEnabled: Boolean = logger.debugEnabled

  override def close() = logger.close()
}
