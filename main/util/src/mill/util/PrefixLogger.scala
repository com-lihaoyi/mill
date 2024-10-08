package mill.util

import mill.api.{Logger, SystemStreams}
import pprint.Util.literalize

import java.io.PrintStream

class PrefixLogger(
    val logger0: ColorLogger,
    key: Seq[String],
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
  val linePrefix: String = if (noPrefix || key.isEmpty) "" else s"[${key.mkString("-")}] "
  override def toString: String =
    s"PrefixLogger($logger0, ${literalize(linePrefix)}, ${literalize(tickerContext)})"
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

  def infoColor = logger0.infoColor
  def errorColor = logger0.errorColor

  val systemStreams = new SystemStreams(
    out = outStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(linePrefix).render,
        logger0.systemStreams.out,
        () => reportKey(key)
      ))
    ),
    err = errStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(linePrefix).render,
        logger0.systemStreams.err,
        () => reportKey(key)
      ))
    ),
    logger0.systemStreams.in
  )

  override def rawOutputStream = logger0.rawOutputStream

  override def info(s: String): Unit = {
    reportKey(key)
    logger0.info(infoColor(linePrefix) + s)
  }
  override def error(s: String): Unit = {
    reportKey(key)
    logger0.error(infoColor(linePrefix) + s)
  }
  override def ticker(s: String): Unit = setPromptDetail(key, s)
  override def setPromptDetail(key: Seq[String], s: String): Unit = logger0.setPromptDetail(key, s)

  private[mill] override def setPromptLine(
      key: Seq[String],
      verboseKeySuffix: String,
      message: String
  ): Unit =
    logger0.setPromptLine(key, verboseKeySuffix, message)

  private[mill] override def setPromptLine(): Unit =
    setPromptLine(key, verboseKeySuffix, message)

  override def debug(s: String): Unit = {
    if (debugEnabled) reportKey(key)
    logger0.debug(infoColor(linePrefix) + s)
  }
  override def debugEnabled: Boolean = logger0.debugEnabled

  override def withOutStream(outStream: PrintStream): PrefixLogger = new PrefixLogger(
    logger0.withOutStream(outStream),
    Seq(infoColor(linePrefix).toString()),
    infoColor(tickerContext).toString(),
    outStream0 = Some(outStream),
    errStream0 = Some(systemStreams.err)
  )
  private[mill] override def reportKey(key: Seq[String]): Unit = logger0.reportKey(key)
  private[mill] override def removePromptLine(key: Seq[String]): Unit =
    logger0.removePromptLine(key)
  private[mill] override def removePromptLine(): Unit = removePromptLine(key)
  private[mill] override def setPromptLeftHeader(s: String): Unit = logger0.setPromptLeftHeader(s)
  override def enableTicker = logger0.enableTicker

  private[mill] override def subLogger(
      path: os.Path,
      subKeySuffix: String,
      message: String
  ): Logger = {
    new PrefixLogger(
      logger0,
      key :+ subKeySuffix,
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
