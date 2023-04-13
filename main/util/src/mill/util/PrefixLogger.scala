package mill.util

import mill.api.SystemStreams

import java.io.PrintStream

class PrefixLogger(out: ColorLogger, context: String, tickerContext: String = "")
    extends ColorLogger {
  override def colored = out.colored

  def infoColor = out.infoColor
  def errorColor = out.errorColor

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
  def apply(out: ColorLogger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)
}
