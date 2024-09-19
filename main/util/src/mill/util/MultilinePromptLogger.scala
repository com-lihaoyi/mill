package mill.util

import mill.api.SystemStreams

import java.io._
import scala.collection.mutable

class MultilinePromptLogger(
    override val colored: Boolean,
    val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    systemStreams0: SystemStreams,
    override val debugEnabled: Boolean
) extends ColorLogger with AutoCloseable {
  import MultilinePromptLogger._
  private val state = new State(systemStreams0, System.currentTimeMillis())
  override def withTicker[T](s: Option[String])(t: => T): T = s match {
    case None => t
    case Some(s) =>
      ticker(s)
      try t
      finally ticker("<END>")
  }
  val systemStreams = new SystemStreams(
    new PrintStream(new StateStream(systemStreams0.out)),
    new PrintStream(new StateStream(systemStreams0.err)),
    systemStreams0.in
  )

  override def close(): Unit = stopped = true

  @volatile var stopped = false
  @volatile var paused = false

  override def withPaused[T](t: => T): T = {
    paused = true
    try t
    finally paused = false
  }

  val secondsTickerThread = new Thread(() => {
    while (!stopped) {
      Thread.sleep(1000)
      if (!paused) {
        synchronized {
          state.refreshPrompt()
        }
      }
    }
  })

  secondsTickerThread.start()

  def info(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def error(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def ticker(s: String): Unit = synchronized {
    state.writeWithPrompt(systemStreams0.err) {
      state.updateCurrent(s)
    }
  }

  def debug(s: String): Unit = synchronized {
    if (debugEnabled) systemStreams.err.println(s)
  }

  override def rawOutputStream: PrintStream = systemStreams0.out

  class StateStream(wrapped: PrintStream) extends OutputStream {
    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      lastIndexOfNewline(b, off, len) match {
        case -1 => wrapped.write(b, off, len)
        case lastNewlineIndex =>
          val indexOfCharAfterNewline = lastNewlineIndex + 1
          // We look for the last newline in the output and use that as an anchor, since
          // we know that after a newline the cursor is at column zero, and column zero
          // is the only place we can reliably position the cursor since the saveCursor and
          // restoreCursor ANSI codes do not work well in the presence of scrolling
          state.writeWithPrompt(wrapped) {
            wrapped.write(b, off, indexOfCharAfterNewline - off)
          }
          wrapped.write(b, indexOfCharAfterNewline, off + len - indexOfCharAfterNewline)
      }
    }

    override def write(b: Int): Unit = synchronized {
      if (b == '\n') state.writeWithPrompt(wrapped)(wrapped.write(b))
      else wrapped.write(b)
    }

    override def flush(): Unit = synchronized {
      wrapped.flush()
    }
  }
}

object MultilinePromptLogger {
  case class Status(startTimeMillis: Long, text: String)

  private class State(systemStreams0: SystemStreams, startTimeMillis: Long) {
    val current: mutable.SortedMap[Int, Seq[Status]] =
      collection.mutable.SortedMap.empty[Int, Seq[Status]]

    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently
    val writePreludeBytes: Array[Byte] = (AnsiNav.clearScreen(0) + AnsiNav.left(9999)).getBytes
    var currentPromptBytes: Array[Byte] = Array[Byte]()
    private def updatePromptBytes() = {
      val now = System.currentTimeMillis()
      val currentPrompt = List("=" * 80 + renderSeconds(now - startTimeMillis)) ++
        current
          .collect {
            case (threadId, statuses) if statuses.nonEmpty =>
              val statusesString = statuses
                .map { status => status.text + renderSeconds(now - status.startTimeMillis) }
                .mkString(" / ")
              statusesString
          }
          .toList

      val currentHeight = currentPrompt.length
      currentPromptBytes =
        (currentPrompt.mkString("\n") + "\n" + AnsiNav.up(currentHeight)).getBytes
    }

    def updateCurrent(s: String): Unit = synchronized {
      val threadId = Thread.currentThread().getId.toInt
      if (s.endsWith("<END>")) current(threadId) = current(threadId).init
      else current(threadId) =
        current.getOrElse(threadId, Nil) :+ Status(System.currentTimeMillis(), s)
      updatePromptBytes()
    }

    def writeWithPrompt[T](wrapped: PrintStream)(t: => T): T = synchronized {
      wrapped.write(writePreludeBytes)
      val res = t
      wrapped.write(currentPromptBytes)
      res
    }

    def refreshPrompt(): Unit = synchronized {
      updatePromptBytes()
      systemStreams0.err.write(currentPromptBytes)
    }

    private def renderSeconds(millis: Long) = (millis / 1000).toInt match {
      case 0 => ""
      case n => s" ${n}s"
    }
  }

  def lastIndexOfNewline(b: Array[Byte], off: Int, len: Int): Int = {
    var index = off + len - 1
    while (true) {
      if (index < off) return -1
      else if (b(index) == '\n') return index
      else index -= 1
    }
    ???
  }
}
