package mill.util

import mill.api.{Logger, SystemStreams}
import pprint.Util.literalize

import java.io.PrintStream

class PrefixLogger(
                    val logger0: ColorLogger,
                    key: String,
                    tickerContext: String = "",
                    outStream0: Option[PrintStream] = None,
                    errStream0: Option[PrintStream] = None,
                    verboseKeySuffix: String = "",
                    message: String = ""
  ) extends ColorLogger {
  val linePrefix: String = if (key == "") "" else s"[$key] "
  override def toString: String =
    s"PrefixLogger($logger0, ${literalize(linePrefix)}, ${literalize(tickerContext)})"
  def this(logger0: ColorLogger, context: String, tickerContext: String) =
    this(logger0, context, tickerContext, None, None)

  override def colored = logger0.colored

  def infoColor = logger0.infoColor
  def errorColor = logger0.errorColor

  val systemStreams = new SystemStreams(
    out = outStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(linePrefix).render,
        logger0.systemStreams.out,
        () => reportPrefix(key)
      ))
    ),
    err = errStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(linePrefix).render,
        logger0.systemStreams.err,
        () => reportPrefix(key)
      ))
    ),
    logger0.systemStreams.in
  )

  override def rawOutputStream = logger0.rawOutputStream

  override def info(s: String): Unit = {
    reportPrefix(key)
    logger0.info(infoColor(linePrefix) + s)
  }
  override def error(s: String): Unit = {
    reportPrefix(key)
    logger0.error(infoColor(linePrefix) + s)
  }
  override def ticker(s: String): Unit = ticker(key, s)
  override def ticker(key: String, s: String): Unit = logger0.ticker(key, s)

  private[mill] override def promptLine(key: String, verboseKeySuffix: String, message: String): Unit =
    logger0.promptLine(key, verboseKeySuffix, message)

  private[mill] override def promptLine(): Unit =
    promptLine(key, verboseKeySuffix, message)

  override def debug(s: String): Unit = {
    if (debugEnabled) reportPrefix(key)
    logger0.debug(infoColor(linePrefix) + s)
  }
  override def debugEnabled: Boolean = logger0.debugEnabled

  override def withOutStream(outStream: PrintStream): PrefixLogger = new PrefixLogger(
    logger0.withOutStream(outStream),
    infoColor(linePrefix).toString(),
    infoColor(tickerContext).toString(),
    outStream0 = Some(outStream),
    errStream0 = Some(systemStreams.err)
  )
  private[mill] override def reportPrefix(s: String): Unit = logger0.reportPrefix(s)
  private[mill] override def endTicker(key: String): Unit = logger0.endTicker(key)
  private[mill] override def endTicker(): Unit = endTicker(key)
  private[mill] override def globalTicker(s: String): Unit = logger0.globalTicker(s)

  override def withPromptPaused[T](t: => T): T = logger0.withPromptPaused(t)

  override def enableTicker = logger0.enableTicker

  override def subLogger(path: os.Path, subKeySuffix: String, message: String): Logger = {
    new PrefixLogger(
      logger0,
      key + subKeySuffix,
      tickerContext,
      outStream0,
      errStream0,
      verboseKeySuffix,
      message
    )
  }
}

object PrefixLogger {
  def apply(out: ColorLogger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)
}
