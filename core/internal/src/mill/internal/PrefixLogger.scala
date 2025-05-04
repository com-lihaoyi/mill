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
private[mill] class PrefixLogger(
    val logger0: Logger,
    key0: Seq[String],
    override val keySuffix: String = "",
    override val message: String = "",
    // Disable printing the prefix, but continue reporting the `key` to `reportKey`. Used
    // for `exclusive` commands where we don't want the prefix, but we do want the header
    // above the output of every command that gets run so we can see who the output belongs to
    noPrefix: Boolean = false
) extends Logger {
  private[mill] override val logKey = logger0.logKey ++ key0

  assert(key0.forall(_.nonEmpty))
  val linePrefix: String =
    if (noPrefix || logKey.isEmpty || !prompt.enableTicker) ""
    else s"[${logKey.mkString("-")}] "
  override def toString: String =
    s"PrefixLogger($logger0, $key0)"

  def prefixPrintStream(stream: java.io.OutputStream) = {
    new PrintStream(new LinePrefixOutputStream(
      prompt.infoColor(linePrefix),
      stream,
      () => prompt.reportKey(logKey)
    ))
  }
  val streams = new SystemStreams(
    out = prefixPrintStream(logger0.unprefixedStreams.out),
    err = prefixPrintStream(logger0.unprefixedStreams.err),
    logger0.streams.in
  )

  private[mill] override val unprefixedStreams = new SystemStreams(
    logger0.unprefixedStreams.out,
    logger0.unprefixedStreams.err,
    logger0.unprefixedStreams.in
  )

  override def info(s: String): Unit = {
    prompt.reportKey(logKey)
    logger0.info("" + prompt.infoColor(linePrefix) + s)
  }
  override def warn(s: String): Unit = {
    prompt.reportKey(logKey)
    logger0.warn("" + prompt.warnColor(linePrefix) + s)
  }
  override def error(s: String): Unit = {
    prompt.reportKey(logKey)
    logger0.error("" + prompt.infoColor(linePrefix) + s)
  }
  override def ticker(s: String): Unit = prompt.setPromptDetail(logKey, s)

  def prompt = logger0.prompt

  override def debug(s: String): Unit = {
    if (prompt.debugEnabled) prompt.reportKey(logKey)
    logger0.debug("" + prompt.infoColor(linePrefix) + s)
  }
  override def withOutStream(outStream: PrintStream): Logger = new ProxyLogger(this) with Logger {
    override lazy val unprefixedStreams = new SystemStreams(
      outStream,
      PrefixLogger.this.unprefixedStreams.err,
      PrefixLogger.this.unprefixedStreams.in
    )

    override lazy val streams = new SystemStreams(
      outStream,
      PrefixLogger.this.streams.err,
      PrefixLogger.this.streams.in
    )
  }
}
