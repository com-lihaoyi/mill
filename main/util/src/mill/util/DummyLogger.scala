package mill.util

import mill.api.{Logger, SystemStreams}

import java.io.{ByteArrayInputStream, PrintStream}


object DummyLogger extends Logger {
  def colored = false

  val systemStreams = new SystemStreams(
    new PrintStream(_ => ()),
    new PrintStream(_ => ()),
    new ByteArrayInputStream(Array())
  )

  def info(s: String) = ()
  def error(s: String) = ()
  def ticker(s: String) = ()
  def debug(s: String) = ()
  override val debugEnabled: Boolean = false
}
