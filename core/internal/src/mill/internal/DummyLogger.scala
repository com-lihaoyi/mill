package mill.internal

import mill.api.{Logger, SystemStreams}

import java.io.{ByteArrayInputStream, PrintStream}

private[mill] object DummyLogger extends Logger {
  def colored = false

  val streams = new SystemStreams(
    new PrintStream(_ => ()),
    new PrintStream(_ => ()),
    new ByteArrayInputStream(Array())
  )

  def info(logKey: Seq[String], s: String) = ()
  def warn(logKey: Seq[String], s: String) = ()
  def error(logKey: Seq[String], s: String) = ()
  def ticker(s: String) = ()
  def debug(logKey: Seq[String], s: String) = ()
  def prompt = new Logger.Prompt.NoOp
}
