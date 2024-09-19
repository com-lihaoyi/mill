package mill.util

import mill.api.SystemStreams

import java.io._

private[mill] class MultilinePromptLogger(
    override val colored: Boolean,
    val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    systemStreams0: SystemStreams,
    override val debugEnabled: Boolean,
    titleText: String,
    terminfoPath: os.Path
) extends ColorLogger with AutoCloseable {
  import MultilinePromptLogger._

  var termWidth = 119
  var termHeight = 50
  private val state = new State(
    titleText,
    enableTicker,
    systemStreams0,
    System.currentTimeMillis(),
    () => termWidth,
    () => termHeight
  )
  val systemStreams = new SystemStreams(
    new PrintStream(new StateStream(systemStreams0.out)),
    new PrintStream(new StateStream(systemStreams0.err)),
    systemStreams0.in
  )

  override def close(): Unit = {
    state.refreshPrompt(ending = true)
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
          val s"$termWidth0 $termHeight0" = os.read(terminfoPath)
          termWidth = termWidth0.toInt
          termHeight = termHeight0.toInt
          state.refreshPrompt()
        }
      }
    }
  })

  promptUpdaterThread.start()

  def info(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def error(s: String): Unit = synchronized { systemStreams.err.println(s) }
  override def globalTicker(s: String): Unit = {
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

private object MultilinePromptLogger {

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
  private val statusRemovalDelayMillis2 = 2500

  private case class Status(startTimeMillis: Long,
                            text: String,
                            var removedTimeMillis: Long)

  private class State(
      titleText: String,
      enableTicker: Boolean,
      systemStreams0: SystemStreams,
      startTimeMillis: Long,
      consoleWidth: () => Int,
      consoleHeight: () => Int,
  ) {
    private val statuses = collection.mutable.SortedMap.empty[Int, Status]

    private var headerPrefix = ""
    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently
    private val writePreludeBytes: Array[Byte] =
      (AnsiNav.clearScreen(0) + AnsiNav.left(9999)).getBytes
    private var currentPromptBytes: Array[Byte] = Array[Byte]()

    private def updatePromptBytes(ending: Boolean = false) = {
      val now = System.currentTimeMillis()
      for (k <- statuses.keySet) {
        val removedTime = statuses(k).removedTimeMillis
        if (now - removedTime > statusRemovalDelayMillis2){
          statuses.remove(k)
        }
      }

      // For the ending prompt, make sure we clear out all
      // the statuses to only show the header alone
      if (ending) statuses.clear()

      // -1 to leave a bit of buffer
      val maxWidth = consoleWidth() - 1
      // -2 to account for 1 line header and 1 line `more threads`
      val maxHeight = math.max(1, consoleHeight() / 3 - 2)
      val headerSuffix = renderSeconds(now - startTimeMillis)

      val header = renderHeader(headerPrefix, titleText, headerSuffix, maxWidth)
      val body0 = statuses
        .collect {
          case (threadId, status) =>
            if (now - status.removedTimeMillis > statusRemovalDelayMillis) ""
            else splitShorten(
              status.text + " " + renderSeconds(now - status.startTimeMillis),
              maxWidth
            )
        }
        .toList
        .sortBy(_.isEmpty)

      val body =
        if (body0.length < maxHeight) body0
        else body0.take(maxHeight) ++ Seq(s"... and ${body0.length - maxHeight} more threads")

      val currentPrompt = header :: body
      val currentHeight = body.length + 1
      // For the ending prompt, leave the cursor at the bottom rather than scrolling back up.
      // We do not want further output to overwrite the header as it will no longer re-render
      val backUp = if (ending) "" else AnsiNav.up(currentHeight)
      currentPromptBytes = (
        AnsiNav.clearScreen(0) +
        currentPrompt.mkString("\n") +
        "\n" +
        backUp
      ).getBytes
    }

    def updateGlobal(s: String): Unit = synchronized {
      headerPrefix = s
      updatePromptBytes()
    }
    def updateCurrent(sOpt: Option[String]): Unit = synchronized {
      val threadId = Thread.currentThread().getId.toInt

      val now = System.currentTimeMillis()
      sOpt match {
        case None => statuses.get(threadId).foreach(_.removedTimeMillis = now)
        case Some(s) => statuses(threadId) = Status(now, s, Long.MaxValue)
      }
      updatePromptBytes()
    }

    def writeWithPrompt[T](wrapped: PrintStream)(t: => T): T = synchronized {
      if (enableTicker) wrapped.write(writePreludeBytes)
      val res = t
      if (enableTicker) wrapped.write(currentPromptBytes)
      res
    }

    def refreshPrompt(ending: Boolean = false): Unit = synchronized {
      updatePromptBytes(ending)
      if (enableTicker) systemStreams0.err.write(currentPromptBytes)
    }

    private def renderSeconds(millis: Long) = (millis / 1000).toInt match {
      case 0 => ""
      case n => s"${n}s"
    }
  }

  def renderHeader(
      headerPrefix0: String,
      titleText0: String,
      headerSuffix0: String,
      maxWidth: Int
  ): String = {
    val headerPrefixStr = s"  $headerPrefix0 "
    val headerSuffixStr = s" $headerSuffix0"
    val titleText = s" $titleText0 "
    // -12 just to ensure we always have some ==== divider on each side of the title
    val maxTitleLength = maxWidth - math.max(headerPrefixStr.length, headerSuffixStr.length) * 2 - 12
    val shortenedTitle = splitShorten(titleText, maxTitleLength)

    // +2 to offset the title a bit to the right so it looks centered, as the `headerPrefixStr`
    // is usually longer than `headerSuffixStr`. We use a fixed offset rather than dynamically
    // offsetting by `headerPrefixStr.length` to prevent the title from shifting left and right
    // as the `headerPrefixStr` changes, even at the expense of it not being perfectly centered.
    val leftDivider = "=" * ((maxWidth / 2) - (titleText.length / 2) - headerPrefixStr.length + 2)
    val rightDivider = "=" * (maxWidth - headerPrefixStr.length - leftDivider.length - shortenedTitle.length - headerSuffixStr.length)
    val headerString = headerPrefixStr + leftDivider + shortenedTitle + rightDivider + headerSuffixStr
    assert(
      headerString.length == maxWidth,
      s"${pprint.apply(headerString)} is length ${headerString.length}, requires $maxWidth"
    )
    headerString
  }

  def splitShorten(s: String, maxLength: Int): String = {
    if (s.length <= maxLength) s
    else {
      val ellipses = "..."
      val halfWidth = (maxLength - ellipses.length) / 2
      s.take(halfWidth) + ellipses + s.takeRight(halfWidth)
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
