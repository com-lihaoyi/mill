package mill.internal

import mill.api.{Logger, SystemStreams}

/**
 * A Logger that forwards all logging to another Logger.  Intended to be
 * used as a base class for wrappers that modify logging behavior.
 */
private[mill] class ProxyLogger(logger: Logger) extends Logger {
  override def toString: String = s"ProxyLogger($logger)"

  lazy val streams = logger.streams

  def info(logKey: Seq[String], s: String): Unit = logger.info(logKey, s)
  def warn(logKey: Seq[String], s: String): Unit = logger.warn(logKey, s)
  def error(logKey: Seq[String], s: String): Unit = logger.error(logKey, s)
  def ticker(s: String): Unit = logger.ticker(s)
  def debug(logKey: Seq[String], s: String): Unit = logger.debug(logKey, s)

  def prompt = logger.prompt

  private[mill] override def logKey: Seq[String] = logger.logKey
  private[mill] override def unprefixedStreams: SystemStreams = logger.unprefixedStreams
}
