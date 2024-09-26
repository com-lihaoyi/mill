package mill.util

import mill.api.SystemStreams
import pprint.Util.literalize

import java.io.PrintStream

class PrefixLogger(
    val logger0: ColorLogger,
    context0: String,
    tickerContext: String = "",
    outStream0: Option[PrintStream] = None,
    errStream0: Option[PrintStream] = None
) extends ColorLogger {
  val context: String = if (context0 == "") "" else context0 + " "
  override def toString: String =
    s"PrefixLogger($logger0, ${literalize(context)}, ${literalize(tickerContext)})"
  def this(logger0: ColorLogger, context: String, tickerContext: String) =
    this(logger0, context, tickerContext, None, None)

  override def colored = logger0.colored

  def infoColor = logger0.infoColor
  def errorColor = logger0.errorColor

  val systemStreams = new SystemStreams(
    out = outStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(context).render,
        logger0.systemStreams.out,
        () => reportPrefix(context0)
      ))
    ),
    err = errStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(context).render,
        logger0.systemStreams.err,
        () => reportPrefix(context0)
      ))
    ),
    logger0.systemStreams.in
  )

  override def rawOutputStream = logger0.rawOutputStream

  override def info(s: String): Unit = {
    reportPrefix(context0)
    logger0.info(infoColor(context) + s)
  }
  override def error(s: String): Unit = {
    reportPrefix(context0)
    logger0.error(infoColor(context) + s)
  }
  override def ticker(s: String): Unit = ticker(context0, s)
  override def ticker(key: String, s: String): Unit = logger0.ticker(key, s)

  private[mill] override def promptLine(key: String, identSuffix: String, message: String): Unit =
    logger0.promptLine(key, identSuffix, message)

  override def debug(s: String): Unit = {
    if (debugEnabled) reportPrefix(context0)
    logger0.debug(infoColor(context) + s)
  }
  override def debugEnabled: Boolean = logger0.debugEnabled

  override def withOutStream(outStream: PrintStream): PrefixLogger = new PrefixLogger(
    logger0.withOutStream(outStream),
    infoColor(context).toString(),
    infoColor(tickerContext).toString(),
    outStream0 = Some(outStream),
    errStream0 = Some(systemStreams.err)
  )
  private[mill] override def reportPrefix(s: String): Unit = {
    logger0.reportPrefix(s)
  }
  private[mill] override def endTicker(key: String): Unit = logger0.endTicker(key)
  private[mill] override def globalTicker(s: String): Unit = logger0.globalTicker(s)

  override def withPromptPaused[T](t: => T): T = logger0.withPromptPaused(t)

  override def enableTicker = logger0.enableTicker
}

object PrefixLogger {
  def apply(out: ColorLogger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)
}
