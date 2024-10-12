package mill.util

import mill.api.{Logger, SystemStreams}

import java.io.PrintStream

class PrefixLogger(
    val logger0: ColorLogger,
    key0: Seq[String],
    tickerContext: String = "",
    outStream0: Option[PrintStream] = None,
    errStream0: Option[PrintStream] = None,
    verboseKeySuffix: String = "",
    message: String = "",
    // Disable printing the prefix, but continue reporting the `key` to `reportKey`. Used
    // for `exclusive` commands where we don't want the prefix but we do want the header
    // above the output of every command that gets run so we can see who the output belongs to
    noPrefix: Boolean = false
) extends ColorLogger {
  private[mill] override val logPrefixKey = logger0.logPrefixKey ++ key0
  assert(key0.forall(_.nonEmpty))
  val linePrefix: String =
    if (noPrefix || logPrefixKey.isEmpty) "" else s"[${logPrefixKey.mkString("-")}] "
  override def toString: String =
    s"PrefixLogger($logger0, $key0)"
  def this(logger0: ColorLogger, context: String, tickerContext: String) =
    this(logger0, Seq(context), tickerContext, None, None)
  def this(
      logger0: ColorLogger,
      context: String,
      tickerContext: String,
      outStream0: Option[PrintStream],
      errStream0: Option[PrintStream]
  ) =
    this(logger0, Seq(context), tickerContext, outStream0, errStream0, "", "")

  override def colored = logger0.colored

  override def infoColor = logger0.infoColor
  override def errorColor = logger0.errorColor

  val systemStreams = new SystemStreams(
    out = outStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(linePrefix).render,
        logger0.unprefixedSystemStreams.out,
        () => reportKey(logPrefixKey)
      ))
    ),
    err = errStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(linePrefix).render,
        logger0.unprefixedSystemStreams.err,
        () => reportKey(logPrefixKey)
      ))
    ),
    logger0.systemStreams.in
  )

  private[mill] override val unprefixedSystemStreams = new SystemStreams(
    outStream0.getOrElse(logger0.unprefixedSystemStreams.out),
    errStream0.getOrElse(logger0.unprefixedSystemStreams.err),
    logger0.unprefixedSystemStreams.in
  )

  override def rawOutputStream = logger0.rawOutputStream

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

  override def withOutStream(outStream: PrintStream): PrefixLogger = new PrefixLogger(
    logger0.withOutStream(outStream),
    logPrefixKey,
    infoColor(tickerContext).toString(),
    outStream0 = Some(outStream),
    errStream0 = Some(systemStreams.err)
  )

  private[mill] override def reportKey(callKey: Seq[String]): Unit =
    logger0.reportKey(callKey)
  private[mill] override def removePromptLine(callKey: Seq[String]): Unit =
    logger0.removePromptLine(callKey)
  private[mill] override def removePromptLine(): Unit = removePromptLine(logPrefixKey)
  private[mill] override def setPromptLeftHeader(s: String): Unit = logger0.setPromptLeftHeader(s)
  override def enableTicker = logger0.enableTicker

  private[mill] override def subLogger(
      path: os.Path,
      subKeySuffix: String,
      message: String
  ): Logger = {
    new PrefixLogger(
      this,
      Seq(subKeySuffix),
      tickerContext,
      outStream0,
      errStream0,
      verboseKeySuffix,
      message
    )
  }
  private[mill] override def withPromptPaused[T](t: => T): T = logger0.withPromptPaused(t)
  private[mill] override def withPromptUnpaused[T](t: => T): T = logger0.withPromptUnpaused(t)
}

object PrefixLogger {
  def apply(out: ColorLogger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)
}
