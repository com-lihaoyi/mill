package mill.util

import java.io._

import ammonite.ops.Path
import ammonite.util.Colors


trait Logger {
  def colored: Boolean
  val outputStream: PrintStream
  def info(s: String): Unit
  def error(s: String): Unit

  /**
    * Like [[info]], but if two calls to [[ticker]] are made consecutively
    * without any calls to [[info]]/[[error]][[outputStream]] in between,
    * the second call to [[ticker]] over-writes the first one in the console.
    * This is useful for displaying loading bars, progress updates or all other
    * sorts of fast-changing information to the user
    */
  def ticker(s: String): Unit
  def close(): Unit = ()
}

object DummyLogger extends Logger {
  def colored = false
  object outputStream extends PrintStream(_ => ())
  def info(s: String) = ()
  def error(s: String) = ()
  def ticker(s: String) = ()
}

case class PrintLogger(colored: Boolean,
                       colors: ammonite.util.Colors,
                       infoStream: PrintStream,
                       errorStream: PrintStream) extends Logger {

  var lastLineTicker = false
  override val outputStream = new PrintStream(
    new OutputStream {
      override def write(b: Array[Byte]): Unit = {
        lastLineTicker = false
        infoStream.write(b)
      }

      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        lastLineTicker = false
        infoStream.write(b, off, len)
      }

      def write(b: Int) = {
        lastLineTicker = false
        infoStream.write(b)
      }
    }
  )

  def info(s: String) = {
    lastLineTicker = false
    infoStream.println(colors.info()(s))
  }
  def error(s: String) = {
    lastLineTicker = false
    errorStream.println(colors.error()(s))
  }
  def ticker(s: String) = {
    if (lastLineTicker){
      val p = new PrintWriter(infoStream)
      val nav = new ammonite.terminal.AnsiNav(p)
      nav.up(1)
      nav.clearLine(2)
      nav.left(9999)
      p.flush()
    }
    lastLineTicker = true
    infoStream.println(colors.info()(s))
  }
}

case class FileLogger(colored: Boolean, file: Path) extends Logger {
  private[this] var outputStreamUsed: Boolean = false

  lazy val outputStream = {
    outputStreamUsed = true
    new PrintStream(new FileOutputStream(file.toIO.getAbsolutePath))
  }

  def info(s: String) = outputStream.println(s)
  def error(s: String) = outputStream.println(s)
  def ticker(s: String) = outputStream.println(s)
  override def close() = {
    if (outputStreamUsed)
      outputStream.close()
  }
}

case class MultiLogger(colored: Boolean, streams: Logger*) extends Logger {
  lazy val outputStream: PrintStream =
    new PrintStream(b => streams.foreach(_.outputStream.write(b))) {
      override def flush() = streams.foreach(_.outputStream.flush())
      override def close() = streams.foreach(_.outputStream.close())
    }

  def info(s: String) = streams.foreach(_.info(s))
  def error(s: String) = streams.foreach(_.error(s))
  def ticker(s: String) = streams.foreach(_.ticker(s))
  override def close() = streams.foreach(_.close())
}