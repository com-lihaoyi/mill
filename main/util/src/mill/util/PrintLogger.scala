package mill.util

import java.io._
import mill.api.SystemStreams

class PrintLogger(
    override val colored: Boolean,
    override val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    val systemStreams: SystemStreams,
    override val debugEnabled: Boolean,
    val context: String,
    printLoggerState: PrintLogger.State
) extends ColorLogger {
  override def toString: String = s"PrintLogger($colored, $enableTicker)"
  def info(s: String): Unit = synchronized {
    printLoggerState.value = PrintLogger.State.Newline
    systemStreams.err.println(infoColor(context + s))
  }

  def error(s: String): Unit = synchronized {
    printLoggerState.value = PrintLogger.State.Newline
    systemStreams.err.println((infoColor(context) ++ errorColor(s)).render)
  }

  override def setPromptDetail(key: Seq[String], s: String): Unit = synchronized { ticker(s) }
  def ticker(s: String): Unit = synchronized {
    if (enableTicker) {
      printLoggerState.value match {
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
      printLoggerState.value = PrintLogger.State.Ticker
    }
  }

  override def withOutStream(outStream: PrintStream): PrintLogger =
    copy(systemStreams = new SystemStreams(outStream, systemStreams.err, systemStreams.in))

  private def copy(
      colored: Boolean = colored,
      enableTicker: Boolean = enableTicker,
      infoColor: fansi.Attrs = infoColor,
      errorColor: fansi.Attrs = errorColor,
      systemStreams: SystemStreams = systemStreams,
      debugEnabled: Boolean = debugEnabled,
      context: String = context,
      printLoggerState: PrintLogger.State = printLoggerState
  ): PrintLogger = new PrintLogger(
    colored,
    enableTicker,
    infoColor,
    errorColor,
    systemStreams,
    debugEnabled,
    context,
    printLoggerState
  )

  def debug(s: String): Unit = synchronized {
    if (debugEnabled) {
      printLoggerState.value = PrintLogger.State.Newline
      systemStreams.err.println(context + s)
    }
  }

  override def rawOutputStream: PrintStream = systemStreams.out
}

object PrintLogger {

  def wrapSystemStreams(systemStreams0: SystemStreams, printLoggerState: State): SystemStreams = {
    new SystemStreams(
      new PrintStream(new PrintLogger.StateStream(systemStreams0.out, printLoggerState.value = _)),
      new PrintStream(new PrintLogger.StateStream(systemStreams0.err, printLoggerState.value = _)),
      systemStreams0.in
    )
  }
  class StateStream(wrapped: OutputStream, setprintLoggerState0: State.Value => Unit)
      extends OutputStream {

    private[this] def setprintLoggerState(c: Char) = setprintLoggerState0(
      c match {
        case '\n' => State.Newline
        case '\r' => State.Newline
        case _ => State.Middle
      }
    )

    override def write(b: Array[Byte]): Unit = synchronized {
      if (b.nonEmpty) setprintLoggerState(b(b.length - 1).toChar)
      wrapped.write(b)
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      if (len != 0) setprintLoggerState(b(off + len - 1).toChar)
      wrapped.write(b, off, len)
    }

    override def write(b: Int): Unit = synchronized {
      setprintLoggerState(b.toChar)
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
