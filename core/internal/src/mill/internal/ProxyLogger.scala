package mill.internal

import mill.api.{Logger, SystemStreams}

/**
 * A Logger that forwards all logging to another Logger.  Intended to be
 * used as a base class for wrappers that modify logging behavior.
 */
private[mill] class ProxyLogger(logger: Logger) extends Logger {
  override def toString: String = s"ProxyLogger($logger)"
  def colored = logger.colored

  lazy val streams = logger.streams

  def info(s: String): Unit = logger.info(s)
  def error(s: String): Unit = logger.error(s)
  def ticker(s: String): Unit = logger.ticker(s)
  def debug(s: String): Unit = logger.debug(s)

  def prompt = logger.prompt

  override def infoColor: fansi.Attrs = logger.infoColor
  override def errorColor: fansi.Attrs = logger.errorColor
  private[mill] override def logPrefixKey: Seq[String] = logger.logPrefixKey
  private[mill] override def unprefixedStreams: SystemStreams = logger.unprefixedStreams
}
