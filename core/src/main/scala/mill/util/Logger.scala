package mill.util

import java.io.{OutputStream, PrintStream}

import ammonite.util.Colors


trait Logger{
  val outputStream: PrintStream
  def info(s: String): Unit
  def error(s: String): Unit
}

object DummyLogger extends Logger{
  object outputStream extends PrintStream(new OutputStream {
    def write(b: Int) = ()
  })
  def info(s: String) = ()
  def error(s: String) = ()
}
class PrintLogger(coloredOutput: Boolean) extends Logger{
  val outputStream = System.err
  val colors =
    if(coloredOutput) Colors.Default
    else Colors.BlackWhite

  def info(s: String) = System.err.println(colors.info()(s))
  def error(s: String) = System.err.println(colors.error()(s))
}