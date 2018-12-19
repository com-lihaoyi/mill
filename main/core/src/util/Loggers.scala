package mill.util

import java.io._
import mill.api.Logger

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

class CallbackStream(wrapped: OutputStream,
                     setPrintState0: PrintState => Unit) extends OutputStream{
  def setPrintState(c: Char) = {
    setPrintState0(
      c match{
        case '\n' => PrintState.Newline
        case '\r' => PrintState.Newline
        case _ => PrintState.Middle
      }
    )
  }
  override def write(b: Array[Byte]): Unit = {
    if (b.nonEmpty) setPrintState(b(b.length-1).toChar)
    wrapped.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    if (len != 0) setPrintState(b(off+len-1).toChar)
    wrapped.write(b, off, len)
  }

  def write(b: Int) = {
    setPrintState(b.toChar)
    wrapped.write(b)
  }
}
sealed trait PrintState
object PrintState{
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


  def info(s: String) = {
    printState = PrintState.Newline
    infoStream.println(colors.info()(s))
  }
  def error(s: String) = {
    printState = PrintState.Newline
    errStream.println(colors.error()(s))
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

  def debug(s: String) = if (debugEnabled) {
    printState = PrintState.Newline
    errStream.println(s)
  }
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
  val inStream: InputStream = mill.api.DummyInputStream
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

/**
  * A Logger that forwards all logging to another Logger.  Intended to be
  * used as a base class for wrappers that modify logging behavior.
  */
case class ProxyLogger(logger: Logger) extends Logger {
  def colored = logger.colored

  lazy val outputStream = logger.outputStream
  lazy val errorStream = logger.errorStream
  lazy val inStream = logger.inStream

  def info(s: String) = logger.info(s)
  def error(s: String) = logger.error(s)
  def ticker(s: String) = logger.ticker(s)
  def debug(s: String) = logger.debug(s)
  override def close() = logger.close()
}
