package mill.util

import java.io._

import ammonite.ops.{Path, rm}
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
  val inStream: InputStream
  def info(s: String): Unit
  def error(s: String): Unit
  def ticker(s: String): Unit
  def close(): Unit = ()
}

object DummyLogger extends Logger {
  def colored = false
  object errorStream extends PrintStream(_ => ())
  object outputStream extends PrintStream(_ => ())
  val inStream = new ByteArrayInputStream(Array())
  def info(s: String) = ()
  def error(s: String) = ()
  def ticker(s: String) = ()
}

class CallbackStream(wrapped: OutputStream, setPrintState0: PrintState => Unit)
    extends OutputStream {
  def setPrintState(c: Char) = {
    setPrintState0(
      c match {
        case '\n' => PrintState.Newline
        case '\r' => PrintState.Newline
        case _    => PrintState.Middle
      }
    )
  }
  override def write(b: Array[Byte]): Unit = {
    if (b.nonEmpty) setPrintState(b(b.length - 1).toChar)
    wrapped.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    if (len != 0) setPrintState(b(off + len - 1).toChar)
    wrapped.write(b, off, len)
  }

  def write(b: Int) = {
    setPrintState(b.toChar)
    wrapped.write(b)
  }
}
sealed trait PrintState
object PrintState {
  case object Ticker extends PrintState
  case object Newline extends PrintState
  case object Middle extends PrintState
}
case class PrintLogger(colored: Boolean,
                       colors: ammonite.util.Colors,
                       outStream: PrintStream,
                       infoStream: PrintStream,
                       errStream: PrintStream,
                       inStream: InputStream)
    extends Logger {

  var printState: PrintState = PrintState.Newline

  override val errorStream = new PrintStream(
    new CallbackStream(errStream, printState = _))
  override val outputStream = new PrintStream(
    new CallbackStream(outStream, printState = _))

  def info(s: String) = {
    printState = PrintState.Newline
    infoStream.println(colors.info()(s))
  }
  def error(s: String) = {
    printState = PrintState.Newline
    errStream.println(colors.error()(s))
  }
  def ticker(s: String) = {
    printState match {
      case PrintState.Newline =>
        infoStream.println(colors.info()(s))
      case PrintState.Middle =>
        infoStream.println()
        infoStream.println(colors.info()(s))
      case PrintState.Ticker =>
        val p = new PrintWriter(infoStream)
        val nav = new ammonite.terminal.AnsiNav(p)
        nav.up(1)
        nav.clearLine(2)
        nav.left(9999)
        p.flush()

        infoStream.println(colors.info()(s))
    }
    printState = PrintState.Ticker
  }
}

case class FileLogger(colored: Boolean, file: Path) extends Logger {
  private[this] var outputStreamUsed: Boolean = false

  lazy val outputStream = {
    if (!outputStreamUsed) rm(file)
    outputStreamUsed = true
    new PrintStream(new FileOutputStream(file.toIO.getAbsolutePath))
  }

  lazy val errorStream = {
    if (!outputStreamUsed) rm(file)
    outputStreamUsed = true
    new PrintStream(new FileOutputStream(file.toIO.getAbsolutePath))
  }

  def info(s: String) = outputStream.println(s)
  def error(s: String) = outputStream.println(s)
  def ticker(s: String) = outputStream.println(s)
  val inStream: InputStream = DummyInputStream
  override def close() = {
    if (outputStreamUsed)
      outputStream.close()
  }
}

class MultiStream(stream1: OutputStream, stream2: OutputStream)
    extends PrintStream(new OutputStream {
      def write(b: Int): Unit = {
        stream1.write(b)
        stream2.write(b)
      }
      override def write(b: Array[Byte]): Unit = {
        stream1.write(b)
        stream2.write(b)
      }
      override def write(b: Array[Byte], off: Int, len: Int) = {
        stream1.write(b, off, len)
        stream2.write(b, off, len)
      }
      override def flush() = {
        stream1.flush()
        stream2.flush()
      }
      override def close() = {
        stream1.close()
        stream2.close()
      }
    })

case class MultiLogger(colored: Boolean, logger1: Logger, logger2: Logger)
    extends Logger {

  lazy val outputStream: PrintStream =
    new MultiStream(logger1.outputStream, logger2.outputStream)

  lazy val errorStream: PrintStream =
    new MultiStream(logger1.errorStream, logger2.errorStream)

  lazy val inStream = Seq(logger1, logger2).collectFirst {
    case t: PrintLogger => t
  } match {
    case Some(x) => x.inStream
    case None    => new ByteArrayInputStream(Array())
  }

  def info(s: String) = {
    logger1.info(s)
    logger2.info(s)
  }
  def error(s: String) = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String) = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  override def close() = {
    logger1.close()
    logger2.close()
  }
}
