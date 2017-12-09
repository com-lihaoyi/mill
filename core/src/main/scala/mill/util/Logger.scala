package mill.util

import java.io.{FileOutputStream, PrintStream}

import ammonite.ops.Path
import ammonite.util.Colors


trait Logger {
  val outputStream: PrintStream
  def info(s: String): Unit
  def error(s: String): Unit
  def close(): Unit = ()
}

object DummyLogger extends Logger {
  object outputStream extends PrintStream(_ => ())
  def info(s: String) = ()
  def error(s: String) = ()
}

case class PrintLogger(coloredOutput: Boolean) extends Logger {
  val outputStream: PrintStream = System.err

  val colors =
    if(coloredOutput) Colors.Default
    else Colors.BlackWhite

  def info(s: String) = outputStream.println(colors.info()(s))
  def error(s: String) = outputStream.println(colors.error()(s))
}

case class FileLogger(file: Path) extends Logger {
  private[this] var outputStreamUsed: Boolean = false

  lazy val outputStream = {
    outputStreamUsed = true
    new PrintStream(new FileOutputStream(file.toIO.getAbsolutePath))
  }

  def info(s: String) = outputStream.println(s)
  def error(s: String) = outputStream.println(s)
  override def close() = {
    if (outputStreamUsed)
      outputStream.close()
  }
}

case class MultiLogger(streams: Logger*) extends Logger {
  lazy val outputStream: PrintStream =
    new PrintStream(b => streams.foreach(_.outputStream.write(b))) {
      override def flush() = streams.foreach(_.outputStream.flush())
      override def close() = streams.foreach(_.outputStream.close())
    }

  def info(s: String) = streams.foreach(_.info(s))
  def error(s: String) = streams.foreach(_.error(s))
  override def close() = streams.foreach(_.close())
}