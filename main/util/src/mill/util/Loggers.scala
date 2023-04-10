package mill.util

import java.io._
import java.nio.file.{Files, StandardOpenOption}
import scala.util.DynamicVariable
import mill.api.{Logger, SystemStreams}

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

  override def write(b: Array[Byte]): Unit = synchronized {
    if (b.nonEmpty) setPrintState(b(b.length - 1).toChar)
    wrapped.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    if (len != 0) setPrintState(b(off + len - 1).toChar)
    wrapped.write(b, off, len)
  }

  override def write(b: Int): Unit = synchronized {
    setPrintState(b.toChar)
    wrapped.write(b)
  }

  override def flush(): Unit = {
    wrapped.flush()
  }
}

sealed trait PrintState

object PrintState {
  case object Ticker extends PrintState
  case object Newline extends PrintState
  case object Middle extends PrintState
}
trait ColorLogger extends Logger {
  def infoColor: fansi.Attrs
  def errorColor: fansi.Attrs
}

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

class AnsiNav(output: Writer) {
  def control(n: Int, c: Char) = output.write("\u001b[" + n + c)

  /**
   * Move up `n` squares
   */
  def up(n: Int) = if (n == 0) "" else control(n, 'A')

  /**
   * Move down `n` squares
   */
  def down(n: Int) = if (n == 0) "" else control(n, 'B')

  /**
   * Move right `n` squares
   */
  def right(n: Int) = if (n == 0) "" else control(n, 'C')

  /**
   * Move left `n` squares
   */
  def left(n: Int) = if (n == 0) "" else control(n, 'D')

  /**
   * Clear the screen
   *
   * n=0: clear from cursor to end of screen
   * n=1: clear from cursor to start of screen
   * n=2: clear entire screen
   */
  def clearScreen(n: Int) = control(n, 'J')

  /**
   * Clear the current line
   *
   * n=0: clear from cursor to end of line
   * n=1: clear from cursor to start of line
   * n=2: clear entire line
   */
  def clearLine(n: Int) = control(n, 'K')
}

object PrefixLogger {
  def apply(out: ColorLogger, context: String, tickerContext: String = ""): PrefixLogger =
    new PrefixLogger(out, context, tickerContext)
}

class PrintLogger(
    override val colored: Boolean,
    val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    val systemStreams0: SystemStreams,
    val infoStream0: PrintStream,
    override val debugEnabled: Boolean,
    val context: String
) extends ColorLogger {

  var printState: PrintState = PrintState.Newline

  override val systemStreams = new SystemStreams(
    new PrintStream(new CallbackStream(systemStreams0.out, printState = _)),
    new PrintStream(new CallbackStream(systemStreams0.err, printState = _)),
    systemStreams0.in
  )
  val infoStream = new PrintStream(new CallbackStream(infoStream0, printState = _))

  def info(s: String) = synchronized {
    printState = PrintState.Newline
    infoStream.println(infoColor(context + s))
  }

  def error(s: String) = synchronized {
    printState = PrintState.Newline
    systemStreams.err.println((infoColor(context) ++ errorColor(s)).render)
  }

  def ticker(s: String) = synchronized {
    if (enableTicker) {
      printState match {
        case PrintState.Newline =>
          infoStream.println(infoColor(s))
        case PrintState.Middle =>
          infoStream.println()
          infoStream.println(infoColor(s))
        case PrintState.Ticker =>
          val p = new PrintWriter(infoStream)
          // Need to make this more "atomic"
          val nav = new AnsiNav(p)
          nav.up(1)
          nav.clearLine(2)
          nav.left(9999)
          p.flush()

          infoStream.println(infoColor(s))
      }
      printState = PrintState.Ticker
    }
  }

  def withOutStream(outStream: PrintStream): PrintLogger =
    copy(systemStreams0 = new SystemStreams(outStream, systemStreams0.err, systemStreams0.in))

  private def copy(
      colored: Boolean = colored,
      enableTicker: Boolean = enableTicker,
      infoColor: fansi.Attrs = infoColor,
      errorColor: fansi.Attrs = errorColor,
      systemStreams0: SystemStreams = systemStreams0,
      infoStream: PrintStream = infoStream,
      debugEnabled: Boolean = debugEnabled,
      context: String = context
  ): PrintLogger = new PrintLogger(
    colored,
    enableTicker,
    infoColor,
    errorColor,
    systemStreams0,
    infoStream,
    debugEnabled,
    context
  )

  def debug(s: String) = synchronized {
    if (debugEnabled) {
      printState = PrintState.Newline
      systemStreams.err.println(context + s)
    }
  }
}

class FileLogger(
    override val colored: Boolean,
    file: os.Path,
    override val debugEnabled: Boolean,
    append: Boolean = false
) extends Logger {
  private[this] var outputStreamUsed: Boolean = false

  lazy val fileStream = {

    val options = Seq(
      Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
      Seq(StandardOpenOption.APPEND).filter(_ => append),
      Seq(StandardOpenOption.TRUNCATE_EXISTING).filter(_ => !append && !outputStreamUsed)
    ).flatten
//    if (!append && !outputStreamUsed) os.remove.all(file)
    outputStreamUsed = true
    var folderCreated = false
    // Lazily create the folder and file that we're logging to, so as to avoid spamming the out/
    // folder with empty folders/files for the vast majority of tasks that do not have any logs
    lazy val inner = {
      if (!os.exists(file / os.up)) os.makeDir.all(file / os.up)
      folderCreated = true
      Files.newOutputStream(file.toNIO, options: _*)
    }
    new PrintStream(new OutputStream {
      override def write(b: Int): Unit = inner.write(b)

      override def write(b: Array[Byte]): Unit = inner.write(b)

      override def write(b: Array[Byte], off: Int, len: Int): Unit = inner.write(b, off, len)

      override def close(): Unit = if (folderCreated) inner.close()

      override def flush(): Unit = if (folderCreated) inner.flush()
    })
  }

  val systemStreams = new SystemStreams(fileStream, fileStream, mill.api.DummyInputStream)
  def info(s: String) = outputStream.println(s)
  def error(s: String) = outputStream.println(s)
  def ticker(s: String) = outputStream.println(s)
  def debug(s: String) = if (debugEnabled) outputStream.println(s)
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

class MultiLogger(
    val colored: Boolean,
    val logger1: Logger,
    val logger2: Logger,
    val inStream0: InputStream,
    override val debugEnabled: Boolean
) extends Logger {

  lazy val systemStreams = new SystemStreams(
    new MultiStream(logger1.systemStreams.out, logger2.systemStreams.out),
    new MultiStream(logger1.systemStreams.err, logger2.systemStreams.err),
    inStream0
  )

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
class ProxyLogger(logger: Logger) extends Logger {
  def colored = logger.colored

  lazy val systemStreams = logger.systemStreams

  def info(s: String) = logger.info(s)
  def error(s: String) = logger.error(s)
  def ticker(s: String) = logger.ticker(s)
  def debug(s: String) = logger.debug(s)

  override def debugEnabled: Boolean = logger.debugEnabled

  override def close() = logger.close()
}
