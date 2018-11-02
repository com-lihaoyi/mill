package mill.util

import java.io._

import ammonite.util.Colors

/**
  * The standard logging interface of the Mill build tool.
  *
  * Contains these primary logging methods, in order of increasing importance:
  *
  * - `debug` : internal debug messages normally not shown to the user;
  * mostly useful when debugging issues
  *
  * - `ticker`: short-lived logging output where consecutive lines over-write
  * each other; useful for information which is transient and disposable
  *
  * - `info`: miscellaneous logging output which isn't part of the main output
  * a user is looking for, but useful to provide context on what Mill is doing
  *
  * - `error`: logging output which represents problems the user should care
  * about
  *
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
  def debug(s: String): Unit

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
  def debug(s: String) = ()
}

class CallbackStream(
  wrapped: OutputStream,
  setPrintState0: PrintState => Unit
) extends OutputStream {

  private[this] var printState: PrintState = _

  private[this] def setPrintState(c: Char) = {
    printState = c match {
      case '\n' => PrintState.Newline
      case '\r' => PrintState.Newline
      case _ => PrintState.Middle
    }
    setPrintState0(printState)
  }

  private[this] def addPrefix(): Unit = {
    PrintLogger.getContext.map { p =>
      if (printState == PrintState.Newline || printState == null) {
        wrapped.write(p.getBytes())
      }
    }
  }

  override def write(b: Array[Byte]): Unit = {
    if (b.nonEmpty) setPrintState(b(b.length - 1).toChar)
    addPrefix()
    wrapped.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    if (len != 0) setPrintState(b(off + len - 1).toChar)
    addPrefix()
    wrapped.write(b, off, len)
  }

  override def write(b: Int): Unit = {
    setPrintState(b.toChar)
    addPrefix()
    wrapped.write(b)
  }
}

sealed trait PrintState

object PrintState {
  case object Ticker extends PrintState
  case object Newline extends PrintState
  case object Middle extends PrintState
}

case class PrintLogger(
  colored: Boolean,
  disableTicker: Boolean,
  colors: ammonite.util.Colors,
  outStream: PrintStream,
  infoStream: PrintStream,
  errStream: PrintStream,
  inStream: InputStream,
  debugEnabled: Boolean
) extends Logger {

  var printState: PrintState = PrintState.Newline

  override val errorStream = new PrintStream(new CallbackStream(errStream, printState = _))
  override val outputStream = new PrintStream(new CallbackStream(outStream, printState = _))

  private[this] def context = PrintLogger.getContext.getOrElse("")

  def info(s: String) = {
    printState = PrintState.Newline
    infoStream.println(context + colors.info()(s))
  }

  def error(s: String) = {
    printState = PrintState.Newline
    errStream.println(context + colors.error()(s))
  }

  def ticker(s: String) = {
    if(!disableTicker) {
      printState match{
        case PrintState.Newline =>
          infoStream.println(colors.info()(s))
        case PrintState.Middle =>
          infoStream.println()
          infoStream.println(colors.info()(s))
        case PrintState.Ticker =>
          val p = new PrintWriter(infoStream)
          // Need to make this more "atomic"
          synchronized {
            val nav = new ammonite.terminal.AnsiNav(p)
            nav.up(1)
            nav.clearLine(2)
            nav.left(9999)
            p.flush()

            infoStream.println(colors.info()(s))
          }
      }
      printState = PrintState.Ticker
    }
  }

  def debug(s: String) = if (debugEnabled) {
    printState = PrintState.Newline
    errStream.println(context + s)
  }
}

object PrintLogger {

  private[this] val _context: InheritableThreadLocal[Option[String]] = new InheritableThreadLocal[Option[String]]() {
    override def initialValue(): Option[String] = None
  }

  def withContext[T](context: Option[String])(f: => T): T = {
    val oldContext = _context.get()
    _context.set(context)
    try {
      f
    } finally {
      _context.set(oldContext)
    }
  }

  def getContext: Option[String] = _context.get()

}

case class FileLogger(colored: Boolean, file: os.Path, debugEnabled: Boolean) extends Logger {
  private[this] var outputStreamUsed: Boolean = false

  lazy val outputStream = {
    if (!outputStreamUsed) os.remove.all(file)
    outputStreamUsed = true
    new PrintStream(new FileOutputStream(file.toIO.getAbsolutePath))
  }

  lazy val errorStream = {
    if (!outputStreamUsed) os.remove.all(file)
    outputStreamUsed = true
    new PrintStream(new FileOutputStream(file.toIO.getAbsolutePath))
  }

  def info(s: String) = outputStream.println(s)
  def error(s: String) = outputStream.println(s)
  def ticker(s: String) = outputStream.println(s)
  def debug(s: String) = if (debugEnabled) outputStream.println(s)
  val inStream: InputStream = DummyInputStream
  override def close() = {
    if (outputStreamUsed)
      outputStream.close()
  }
}



class MultiStream(stream1: OutputStream, stream2: OutputStream) extends PrintStream(new OutputStream {
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

case class MultiLogger(colored: Boolean, logger1: Logger, logger2: Logger) extends Logger {


  lazy val outputStream: PrintStream = new MultiStream(logger1.outputStream, logger2.outputStream)

  lazy val errorStream: PrintStream = new MultiStream(logger1.errorStream, logger2.errorStream)

  lazy val inStream = Seq(logger1, logger2).collectFirst{case t: PrintLogger => t} match{
    case Some(x) => x.inStream
    case None => new ByteArrayInputStream(Array())
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

  def debug(s: String) = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def close() = {
    logger1.close()
    logger2.close()
  }
}
