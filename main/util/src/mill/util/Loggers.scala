package mill.util

import java.io._
import java.nio.file.{Files, StandardOpenOption}
import scala.util.DynamicVariable
import mill.api.{Logger, SystemStreams}

class PrintLogger(
    override val colored: Boolean,
    val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    val systemStreams0: SystemStreams,
    override val debugEnabled: Boolean,
    val context: String,
    printState: PrintLogger.State
) extends ColorLogger {

  override val systemStreams = new SystemStreams(
    new PrintStream(new PrintLogger.StateStream(systemStreams0.out, printState.value = _)),
    new PrintStream(new PrintLogger.StateStream(systemStreams0.err, printState.value = _)),
    systemStreams0.in
  )

  def info(s: String) = synchronized {
    printState.value = PrintLogger.State.Newline
    systemStreams.err.println(infoColor(context + s))
  }

  def error(s: String) = synchronized {
    printState.value = PrintLogger.State.Newline
    systemStreams.err.println((infoColor(context) ++ errorColor(s)).render)
  }

  def ticker(s: String) = synchronized {
    if (enableTicker) {
      printState.value match {
        case PrintLogger.State.Newline =>
          systemStreams.err.println(infoColor(s))
        case PrintLogger.State.Middle =>
          systemStreams.err.println()
          systemStreams.err.println(infoColor(s))
        case PrintLogger.State.Ticker =>
          val p = new PrintWriter(systemStreams.err)
          // Need to make this more "atomic"
          val nav = new AnsiNav(p)
          nav.up(1)
          nav.clearLine(2)
          nav.left(9999)
          p.flush()

          systemStreams.err.println(infoColor(s))
      }
      printState.value = PrintLogger.State.Ticker
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
                    debugEnabled: Boolean = debugEnabled,
                    context: String = context,
                    printState: PrintLogger.State = printState,
  ): PrintLogger = new PrintLogger(
    colored,
    enableTicker,
    infoColor,
    errorColor,
    systemStreams0,
    debugEnabled,
    context,
    printState
  )

  def debug(s: String) = synchronized {
    if (debugEnabled) {
      printState.value = PrintLogger.State.Newline
      systemStreams.err.println(context + s)
    }
  }
}

object PrintLogger{

  class StateStream(wrapped: OutputStream,
                    setPrintState0: State.Value => Unit) extends OutputStream {


    private[this] def setPrintState(c: Char) = setPrintState0(
      c match {
        case '\n' => State.Newline
        case '\r' => State.Newline
        case _ => State.Middle
      }
    )

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

    override def flush(): Unit = synchronized {
      wrapped.flush()
    }
  }

  class State {
    var value: State.Value = State.Newline
  }

  object State {
    sealed trait Value
    case object Ticker extends Value
    case object Newline extends Value
    case object Middle extends Value
  }
}

