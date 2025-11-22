package mill.internal

import mill.api.{Logger, SystemStreams}

/**
 * A Logger that forwards all logging to another Logger.  Intended to be
 * used as a base class for wrappers that modify logging behavior.
 */
class ProxyLogger(logger: Logger) extends Logger {
  override def toString: String = s"ProxyLogger($logger)"

  lazy val streams = logger.streams

  def info(s: String): Unit = logger.info(s)
  def warn(s: String): Unit = logger.warn(s)
  def error(s: String): Unit = logger.error(s)
  def ticker(s: String): Unit = logger.ticker(s)
  def debug(s: String): Unit = logger.debug(s)

  def prompt = logger.prompt

  override def logKey: Seq[String] = logger.logKey
  override def unprefixedStreams: SystemStreams = logger.unprefixedStreams

  override def redirectOutToErr: Boolean = logger.redirectOutToErr

  override def withRedirectOutToErr() = new ProxyLogger(logger.withRedirectOutToErr())
}
