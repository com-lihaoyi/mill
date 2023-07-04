package mill.util

import mill.api.{Logger, SystemStreams}

import java.io.PrintStream

class PrefixLogger(out: Logger, context: String, tickerContext: String = "")
    extends ColorLogger {

  @deprecated("Binary compatibility shim", "Mill 0.11.2")
  def this(out: ColorLogger, context: String, tickerContext: String) = {
    this(out: Logger, context, tickerContext)
  }

  private val colorLogger = out match {
    case c: ColorLogger => Some(c)
    case _ => None
  }

  override def colored = colorLogger.fold(false)(_.colored)

  def infoColor = colorLogger.fold(mill.util.Colors.BlackWhite.info)(_.infoColor)
  def errorColor = colorLogger.fold(mill.util.Colors.BlackWhite.error)(_.infoColor)

  val systemStreams = new SystemStreams(
    new PrintStream(new LinePrefixOutputStream(
      infoColor(context).render,
      out.systemStreams.err
    )),
    new PrintStream(new LinePrefixOutputStream(
      infoColor(context).render,
      out.systemStreams.out
    )),
    out.systemStreams.in
  )

  override def info(s: String): Unit = out.info(context + s)

  override def error(s: String): Unit = out.error(context + s)

  override def ticker(s: String): Unit = out.ticker(context + tickerContext + s)

  override def debug(s: String): Unit = out.debug(context + s)

  override def debugEnabled: Boolean = out.debugEnabled
}

object PrefixLogger {
  def apply(out: Logger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)

  @deprecated("Binary compatibility shim", "Mill 0.11.2")
  def apply(out: ColorLogger, context: String, tickerContext: String): PrefixLogger =
    new PrefixLogger(out: Logger, context, tickerContext)
}
