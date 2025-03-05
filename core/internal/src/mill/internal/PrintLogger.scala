package mill.internal

import mill.api.{Logger, SystemStreams}

import java.io.*

private[mill] class PrintLogger(
    override val colored: Boolean,
    override val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    val streams: SystemStreams,
    override val debugEnabled: Boolean,
    val context: String,
    printLoggerState: PrintLogger.State
) extends Logger with AutoCloseable {
  def close() = () // do nothing
  override def toString: String = s"PrintLogger($colored, $enableTicker)"
  def info(s: String): Unit = synchronized {
    printLoggerState.value = PrintLogger.State.Newline
    streams.err.println(infoColor(context + s))
  }

  def error(s: String): Unit = synchronized {
    printLoggerState.value = PrintLogger.State.Newline
    streams.err.println((infoColor(context) ++ errorColor(s)).render)
  }

  def prompt = new Logger.Prompt.NoOp {
    override def setPromptDetail(key: Seq[String], s: String): Unit = synchronized {
      ticker(s)
    }
  }
  def ticker(s: String): Unit = synchronized {
    if (enableTicker) {
      printLoggerState.value match {
        case PrintLogger.State.Newline =>
          streams.err.println(infoColor(s))
        case PrintLogger.State.Middle =>
          streams.err.println()
          streams.err.println(infoColor(s))
        case PrintLogger.State.Ticker =>
          val p = new PrintWriter(streams.err)
          // Need to make this more "atomic"
          val nav = new AnsiNav(p)
          nav.up(1)
          nav.clearLine(2)
          nav.left(9999)
          p.flush()

          streams.err.println(infoColor(s))
      }
      printLoggerState.value = PrintLogger.State.Ticker
    }
  }

  override def withOutStream(outStream: PrintStream): PrintLogger = new PrintLogger(
    colored,
    enableTicker,
    infoColor,
    errorColor,
    new SystemStreams(outStream, streams.err, streams.in),
    debugEnabled,
    context,
    printLoggerState
  )

  def debug(s: String): Unit = synchronized {
    if (debugEnabled) {
      printLoggerState.value = PrintLogger.State.Newline
      streams.err.println(context + s)
    }
  }
}

private[mill] object PrintLogger {

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
