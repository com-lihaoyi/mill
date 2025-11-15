package mill.internal

import mill.api.{Logger, SystemStreams}

import java.io.PrintStream

/**
 * Configures a logger that prefixes lines of logs.
 *
 * Generates log lines of the form
 *
 * [$parentKeys-$key0/$keySuffix] $message
 * [$parentKeys-$key0] ...logs...
 * [$parentKeys-$key0] ...logs...
 * [$parentKeys-$key0] ...logs...
 *
 * And a prompt line of the form
 *
 * [$parentKeys-$key0] $message
 */
private[mill] case class PrefixLogger(
    logger0: Logger,
    key0: Seq[String],
    override val keySuffix: String = "",
    override val message: String = "",
    // Disable printing the prefix, but continue reporting the `key` to `logPrefixedLine`. Used
    // for `exclusive` commands where we don't want the prefix, but we do want the header
    // above the output of every command that gets run so we can see who the output belongs to
    noPrefix: Boolean = false,
    redirectOutToErr0: Option[Boolean] = None
) extends Logger {

  override val redirectOutToErr = redirectOutToErr0 match{
    case None => logger0.redirectOutToErr
    case Some(b) => b
  }
  private[mill] override val logKey = logger0.logKey ++ key0

  assert(key0.forall(_.nonEmpty))
  val linePrefix: String = Logger.formatPrefix(
    if (noPrefix || logKey.isEmpty || !prompt.enableTicker) Nil else logKey
  )

  override def toString: String =
    s"PrefixLogger($logger0, $key0)"

  def prefixPrintStream(logToOut: Boolean) = {
    new PrintStream(
      new LineBufferingOutputStream(logMsg => prompt.logPrefixedLine(logKey, logMsg, logToOut))
    )
  }
  val streams = new SystemStreams(
    out = prefixPrintStream(true && !redirectOutToErr),
    err = prefixPrintStream(false),
    logger0.streams.in
  )

  private[mill] override val unprefixedStreams = new SystemStreams(
    if (redirectOutToErr) logger0.unprefixedStreams.err else logger0.unprefixedStreams.out,
    logger0.unprefixedStreams.err,
    logger0.unprefixedStreams.in
  )

  override def info(s: String): Unit = {
    val baos = new java.io.ByteArrayOutputStream()
    baos.write(s.getBytes)
    baos.write('\n')
    prompt.logPrefixedLine(logKey, baos, false && !redirectOutToErr)
  }
  override def warn(s: String): Unit = {
    val baos = new java.io.ByteArrayOutputStream()
    baos.write(s.getBytes)
    baos.write('\n')
    prompt.logPrefixedLine(logKey, baos, false && !redirectOutToErr)
  }
  override def error(s: String): Unit = {
    val baos = new java.io.ByteArrayOutputStream()
    baos.write(s.getBytes)
    baos.write('\n')
    prompt.logPrefixedLine(logKey, baos, false && !redirectOutToErr)
  }
  override def ticker(s: String): Unit = prompt.setPromptDetail(logKey, s)

  def prompt = logger0.prompt

  override def debug(s: String): Unit = {
    if (debugEnabled) {
      if (prompt.debugEnabled) {
        val baos = new java.io.ByteArrayOutputStream()
        baos.write(s.getBytes)
        baos.write('\n')
        prompt.logPrefixedLine(logKey, baos, false && !redirectOutToErr)
      }
    }
  }

  override def withRedirectOutToErr() = this.copy(redirectOutToErr0 = Some(true))
}
