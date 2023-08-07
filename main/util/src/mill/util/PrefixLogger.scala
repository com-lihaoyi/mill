package mill.util

import mill.api.SystemStreams

import java.io.PrintStream

class PrefixLogger(
    val logger0: ColorLogger,
    context: String,
    tickerContext: String = "",
    outStream0: Option[PrintStream] = None,
    errStream0: Option[PrintStream] = None
) extends ColorLogger {

  def this(logger0: ColorLogger, context: String, tickerContext: String) =
    this(logger0, context, tickerContext, None, None)

  override def colored = logger0.colored

  def infoColor = logger0.infoColor
  def errorColor = logger0.errorColor

  val systemStreams = new SystemStreams(
    out = outStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(context).render,
        logger0.systemStreams.out
      ))
    ),
    err = errStream0.getOrElse(
      new PrintStream(new LinePrefixOutputStream(
        infoColor(context).render,
        logger0.systemStreams.err
      ))
    ),
    logger0.systemStreams.in
  )

  override def info(s: String): Unit = logger0.info(context + s)
  override def error(s: String): Unit = logger0.error(context + s)
  override def ticker(s: String): Unit = logger0.ticker(context + tickerContext + s)
  override def debug(s: String): Unit = logger0.debug(context + s)
  override def debugEnabled: Boolean = logger0.debugEnabled

  override def withOutStream(outStream: PrintStream): PrefixLogger = new PrefixLogger(
    logger0.withOutStream(outStream),
    context,
    tickerContext,
    outStream0 = Some(outStream),
    errStream0 = Some(systemStreams.err)
  )
}

object PrefixLogger {
  def apply(out: ColorLogger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)
}
