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
  private val state = new State(enableTicker, systemStreams0, System.currentTimeMillis())
  val systemStreams = new SystemStreams(
    new PrintStream(new StateStream(systemStreams0.out)),
    new PrintStream(new StateStream(systemStreams0.err)),
    systemStreams0.in
  )

  override def close(): Unit = {
    state.refreshPrompt()
    stopped = true
  }

  @volatile var stopped = false
  @volatile var paused = false

  override def withPaused[T](t: => T): T = {
    paused = true
    try t
    finally paused = false
  }

  val promptUpdaterThread = new Thread(() => {
    while (!stopped) {
      Thread.sleep(promptUpdateIntervalMillis)
      if (!paused) {
        synchronized {
          state.refreshPrompt()
        }
      }
    }
  })

  promptUpdaterThread.start()

  def info(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def error(s: String): Unit = synchronized { systemStreams.err.println(s) }
  def globalTicker(s: String): Unit = {
    state.updateGlobal(s)
  }
  override def endTicker(): Unit = synchronized {
    state.updateCurrent(None)
  }

  def ticker(s: String): Unit = synchronized {
    state.updateCurrent(Some(s))
  }

  def debug(s: String): Unit = synchronized {
    if (debugEnabled) systemStreams.err.println(s)
  }

  override def rawOutputStream: PrintStream = systemStreams0.out

  private class StateStream(wrapped: PrintStream) extends OutputStream {
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
  /**
   * How often to update the multiline status prompt on the terminal.
   * Too frequent is bad because it causes a lot of visual noise,
   * but too infrequent results in latency. 10 times per second seems reasonable
   */
  private val promptUpdateIntervalMillis = 100
  /**
   * Add some extra latency delay to the process of removing an entry from the status
   * prompt entirely, because removing an entry changes the height of the prompt, which
   * is even more distracting than changing the contents of a line, so we want to minimize
   * those occurrences even further.
   */
  private val statusRemovalDelayMillis = 500

  private case class Status(startTimeMillis: Long, text: String, var removedTimeMillis: Long)

  private class State(enableTicker: Boolean, systemStreams0: SystemStreams, startTimeMillis: Long) {
    private val statuses = collection.mutable.SortedMap.empty[Int, Status]

    private var header = ""
    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently
    private val writePreludeBytes: Array[Byte] = (AnsiNav.clearScreen(0) + AnsiNav.left(9999)).getBytes
    private var currentPromptBytes: Array[Byte] = Array[Byte]()

    private def updatePromptBytes() = {
      val now = System.currentTimeMillis()
      for(k <- statuses.keySet){
        val removedTime = statuses(k).removedTimeMillis
        if (removedTime != -1 && now - removedTime > statusRemovalDelayMillis){
          statuses.remove(k)
        }
      }
      // Limit to <120 chars wide
      val maxWidth = 119

      val totalSecondsStr = renderSeconds(now - startTimeMillis)
      val divider = "=" * (maxWidth - header.length - totalSecondsStr.length - 4)
      val currentPrompt = List(s"  $header $divider $totalSecondsStr") ++
        statuses
          .collect {
            case (threadId, status) =>
              val statusesString = status.text + " " + renderSeconds(now - status.startTimeMillis)

              if (statusesString.length <= maxWidth) statusesString
              else {
                val ellipses = "..."
                val halfWidth = (maxWidth - ellipses.length) / 2
                statusesString.take(halfWidth) + ellipses + statusesString.takeRight(halfWidth)
              }
          }
          .toList

      val currentHeight = currentPrompt.length
      currentPromptBytes =
        (AnsiNav.clearScreen(0) + currentPrompt.mkString("\n") + "\n" + AnsiNav.up(currentHeight)).getBytes
    }

    def updateGlobal(s: String): Unit = synchronized{
      header = s
      updatePromptBytes()
    }
    def updateCurrent(sOpt: Option[String]): Unit = synchronized {
      val threadId = Thread.currentThread().getId.toInt

      val now = System.currentTimeMillis()
      sOpt match {
        case None => statuses.get(threadId).foreach(_.removedTimeMillis = now)
        case Some(s) => statuses(threadId) = Status(now, s, -1)
      }
      updatePromptBytes()
    }

    def writeWithPrompt[T](wrapped: PrintStream)(t: => T): T = synchronized {
      if (enableTicker) wrapped.write(writePreludeBytes)
      val res = t
      if (enableTicker) wrapped.write(currentPromptBytes)
      res
    }

    def refreshPrompt(): Unit = synchronized {
      updatePromptBytes()
      if (enableTicker) systemStreams0.err.write(currentPromptBytes)
    }

    private def renderSeconds(millis: Long) = (millis / 1000).toInt match {
      case 0 => ""
      case n => s"${n}s"
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
