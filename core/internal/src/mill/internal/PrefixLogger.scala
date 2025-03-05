package mill.internal

import mill.api.{Logger, SystemStreams}

import java.io.PrintStream

/**
 * Configures a logger that prefixes lines of logs.
 *
 * Generates log lines of the form
 *
 * [$logPrefixKey] $verboseKeySuffix
 * [$logPrefixKey] ...logs...
 * [$logPrefixKey] ...logs...
 * [$logPrefixKey] ...logs...
 *
 * And a prompt line of the form
 *
 * [$logPrefixKey] $message
 */
private[mill] class PrefixLogger(
    val logger0: Logger,
    key0: Seq[String],
    verboseKeySuffix: String = "",
    message: String = "",
    // Disable printing the prefix, but continue reporting the `key` to `reportKey`. Used
    // for `exclusive` commands where we don't want the prefix, but we do want the header
    // above the output of every command that gets run so we can see who the output belongs to
    noPrefix: Boolean = false
) extends Logger {
  private[mill] override val logPrefixKey = logger0.logPrefixKey ++ key0
  assert(key0.forall(_.nonEmpty))
  val linePrefix: String =
    if (noPrefix || logPrefixKey.isEmpty) "" else s"[${logPrefixKey.mkString("-")}] "
  override def toString: String =
    s"PrefixLogger($logger0, $key0)"

  override def colored = logger0.colored

  override def infoColor = logger0.infoColor
  override def errorColor = logger0.errorColor

  def prefixPrintStream(stream: java.io.OutputStream) = {
    new PrintStream(new LinePrefixOutputStream(
      infoColor(linePrefix).render,
      stream,
      () => reportKey(logPrefixKey)
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
    reportKey(logPrefixKey)
    logger0.info("" + infoColor(linePrefix) + s)
  }
  override def error(s: String): Unit = {
    reportKey(logPrefixKey)
    logger0.error("" + infoColor(linePrefix) + s)
  }
  override def ticker(s: String): Unit = setPromptDetail(logPrefixKey, s)
  override def setPromptDetail(key: Seq[String], s: String): Unit = logger0.setPromptDetail(key, s)

  private[mill] override def setPromptLine(
      callKey: Seq[String],
      verboseKeySuffix: String,
      message: String
  ): Unit = {

    logger0.setPromptLine(callKey, verboseKeySuffix, message)
  }

  private[mill] override def setPromptLine(): Unit =
    setPromptLine(logPrefixKey, verboseKeySuffix, message)

  override def debug(s: String): Unit = {
    if (debugEnabled) reportKey(logPrefixKey)
    logger0.debug("" + infoColor(linePrefix) + s)
  }
  override def debugEnabled: Boolean = logger0.debugEnabled

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
  private[mill] override def reportKey(callKey: Seq[String]): Unit =
    logger0.reportKey(callKey)
  private[mill] override def removePromptLine(callKey: Seq[String]): Unit =
    logger0.removePromptLine(callKey)
  private[mill] override def removePromptLine(): Unit = removePromptLine(logPrefixKey)
  private[mill] override def setPromptHeaderPrefix(s: String): Unit =
    logger0.setPromptHeaderPrefix(s)
  override def enableTicker = logger0.enableTicker

  private[mill] override def subLogger(
      path: os.Path,
      subKeySuffix: String,
      message: String
  ): Logger = {
    new PrefixLogger(
      this,
      Seq(subKeySuffix),
      verboseKeySuffix,
      message
    )
  }
  private[mill] override def withPromptPaused[T](t: => T): T = logger0.withPromptPaused(t)
  private[mill] override def withPromptUnpaused[T](t: => T): T = logger0.withPromptUnpaused(t)
}
