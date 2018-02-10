package mill.util

import java.io._

import ammonite.ops.Path
import ammonite.util.Colors


/**
  * The standard logging interface of the Mill build tool.
  *
  * Contains four primary logging methods, in order of increasing importance:
  *
  * - `ticker`: short-lived logging output where consecutive lines over-write
  *   each other; useful for information which is transient and disposable
  *
  * - `info`: miscellaneous logging output which isn't part of the main output
  *   a user is looking for, but useful to provide context on what Mill is doing
  *
  * - `error`: logging output which represents problems the user should care
  *   about
  *
  * Also contains the two forwarded stdout and stderr streams, for code executed
  * by Mill to use directly. Typically these correspond to the stdout and stderr,
  * but when `show` is used both are forwarded to stderr and stdout is only
  * used to display the final `show` output for easy piping.
  */
trait Logger {
  def colored: Boolean
  val errorStream: PrintStream
  val outputStream: PrintStream
  def info(s: String): Unit
  def error(s: String): Unit
  def ticker(s: String): Unit
  def close(): Unit = ()
}

object DummyLogger extends Logger {
  def colored = false
  object errorStream extends PrintStream(_ => ())
  object outputStream extends PrintStream(_ => ())
  def info(s: String) = ()
  def error(s: String) = ()
  def ticker(s: String) = ()
}

class CallbackStream(wrapped: OutputStream, f: () => Unit) extends OutputStream{
  override def write(b: Array[Byte]): Unit = { f(); wrapped.write(b) }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    f()
    wrapped.write(b, off, len)
  }

  def write(b: Int) = {f(); wrapped.write(b)}
}
case class PrintLogger(colored: Boolean,
                       colors: ammonite.util.Colors,
                       outStream: PrintStream,
                       infoStream: PrintStream,
                       errStream: PrintStream) extends Logger {

  var lastLineTicker = false
  def falseTicker[T](t: T) = {
    lastLineTicker = false
    t
  }
  override val errorStream = new PrintStream(
    new CallbackStream(errStream, () => lastLineTicker = false)
  )
  override val outputStream = new PrintStream(
    new CallbackStream(outStream, () => lastLineTicker = false)
  )


  def info(s: String) = {
    lastLineTicker = false
    infoStream.println(colors.info()(s))
  }
  def error(s: String) = {
    lastLineTicker = false
    errStream.println(colors.error()(s))
  }
  def ticker(s: String) = {
    if (lastLineTicker){
      val p = new PrintWriter(infoStream)
      val nav = new ammonite.terminal.AnsiNav(p)
      nav.up(1)
      nav.clearLine(2)
      nav.left(9999)
      p.flush()
    }else{
      infoStream.println()
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

  lazy val errorStream = {
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
  lazy val errorStream: PrintStream =
    new PrintStream(b => streams.foreach(_.outputStream.write(b))) {
      override def flush() = streams.foreach(_.outputStream.flush())
      override def close() = streams.foreach(_.outputStream.close())
    }

  def info(s: String) = streams.foreach(_.info(s))
  def error(s: String) = streams.foreach(_.error(s))
  def ticker(s: String) = streams.foreach(_.ticker(s))
  override def close() = streams.foreach(_.close())
}