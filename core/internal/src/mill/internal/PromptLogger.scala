package mill.internal

import mill.api.{SystemStreams, Logger}
import mill.constants.ProxyStream
import mill.internal.PromptLoggerUtil.*
import pprint.Util.literalize

import java.io.*

/**
 * Gnarly multithreaded stateful code to handle the terminal prompt and log prefixer
 * that Mill shows to tell the user what is running.
 *
 * Most operations that update mutable state *or* writes to parent [[systemStreams0]] is
 * synchronized under the [[PromptLogger]] object. Notably, child writes to
 * [[streams]] are *not* synchronized, and instead goes into a [[PipeStreams]]
 * buffer to be read out and handled asynchronously.
 */
private[mill] class PromptLogger(
    colored: Boolean,
    enableTicker: Boolean,
    infoColor: fansi.Attrs,
    warnColor: fansi.Attrs,
    errorColor: fansi.Attrs,
    systemStreams0: SystemStreams,
    debugEnabled: Boolean,
    titleText: String,
    terminfoPath: os.Path,
    currentTimeMillis: () => Long,
    autoUpdate: Boolean = true
) extends Logger with AutoCloseable {
  override def toString: String = s"PromptLogger(${literalize(titleText)})"
  import PromptLogger.*

  private var termDimensions: (Option[Int], Option[Int]) = (None, None)

  readTerminalDims(terminfoPath).foreach(termDimensions = _)

  def isInteractive() = termDimensions._1.nonEmpty

  private object promptLineState extends PromptLineState(
        titleText,
        currentTimeMillis(),
        () => termDimensions,
        currentTimeMillis,
        infoColor
      )

  private object streamManager extends StreamManager(
        enableTicker,
        systemStreams0,
        () => promptLineState.getCurrentPrompt(),
        interactive = () => isInteractive(),
        paused = () => runningState.paused,
        synchronizer = this
      )

  private object runningState extends RunningState(
        enableTicker,
        () => promptUpdaterThread.interrupt(),
        clearOnPause = () => streamManager.clearOnPause(),
        synchronizer = this
      )

  if (enableTicker) refreshPrompt()

  val promptUpdaterThread = new Thread(
    () => {
      var lastUpdate = System.currentTimeMillis()
      while (!runningState.stopped) {
        try Thread.sleep(promptUpdateIntervalMillis)
        catch {
          case e: InterruptedException => /*do nothing*/
        }

        readTerminalDims(terminfoPath).foreach(termDimensions = _)

        val now = System.currentTimeMillis()
        if (
          isInteractive() ||
          (now - lastUpdate > nonInteractivePromptUpdateIntervalMillis)
        ) {
          lastUpdate = now
          synchronized {
            if (!runningState.paused && !runningState.stopped) refreshPrompt()
          }
        }
      }
    },
    "prompt-logger-updater-thread"
  )

  def refreshPrompt(ending: Boolean = false): Unit = synchronized {
    val updated = promptLineState.updatePrompt(ending)
    if (updated) streamManager.refreshPrompt()
  }

  if (enableTicker && autoUpdate) promptUpdaterThread.start()

  def info(s: String): Unit = streams.err.println(s)

  def warn(s: String): Unit = streams.err.println(s)

  def error(s: String): Unit = streams.err.println(s)

  object prompt extends Logger.Prompt {
    override def setPromptHeaderPrefix(s: String): Unit = PromptLogger.this.synchronized {
      promptLineState.setHeaderPrefix(s)
    }

    override def clearPromptStatuses(): Unit = PromptLogger.this.synchronized {
      promptLineState.clearStatuses()
    }

    override def removePromptLine(key: Seq[String]): Unit = PromptLogger.this.synchronized {
      promptLineState.setCurrent(key, None)
    }

    override def setPromptDetail(key: Seq[String], s: String): Unit =
      PromptLogger.this.synchronized {
        promptLineState.setDetail(key, s)
      }

    override def reportKey(key: Seq[String]): Unit = {
      val res = PromptLogger.this.synchronized {
        if (reportedIdentifiers(key)) None
        else {
          reportedIdentifiers.add(key)
          seenIdentifiers.get(key)
        }
      }
      for ((keySuffix, message) <- res) {
        if (prompt.enableTicker) {
          streams.err.println(
            infoColor(s"[${key.mkString("-")}$keySuffix]${spaceNonEmpty(message)}")
          )
          streamManager.awaitPumperEmpty()
        }
      }
    }

    override def setPromptLine(key: Seq[String], keySuffix: String, message: String): Unit =
      PromptLogger.this.synchronized {
        promptLineState.setCurrent(key, Some(s"[${key.mkString("-")}]${spaceNonEmpty(message)}"))
        seenIdentifiers(key) = (keySuffix, message)
      }

    private[mill] override def withPromptPaused[T](t: => T): T =
      runningState.withPromptPaused0(true, t)

    private[mill] override def withPromptUnpaused[T](t: => T): T =
      runningState.withPromptPaused0(false, t)

    def enableTicker = PromptLogger.this.enableTicker
    def debugEnabled = PromptLogger.this.debugEnabled

    def infoColor(s: String): String = PromptLogger.this.infoColor(s).render
    def warnColor(s: String): String = PromptLogger.this.warnColor(s).render
    def errorColor(s: String): String = PromptLogger.this.errorColor(s).render
    def colored: Boolean = PromptLogger.this.colored
  }
  def ticker(s: String): Unit = ()

  def streamsAwaitPumperEmpty(): Unit = streamManager.awaitPumperEmpty()
  private val seenIdentifiers = collection.mutable.Map.empty[Seq[String], (String, String)]
  private val reportedIdentifiers = collection.mutable.Set.empty[Seq[String]]

  def debug(s: String): Unit = if (debugEnabled) streams.err.println(s)

  override def close(): Unit = {
    synchronized {
      if (enableTicker) refreshPrompt(ending = true)
    }

    // Has to be outside the synchronized block so it can allow the pumper thread
    // to continue pumping out the last data in the streams and terminate
    streamManager.close()

    synchronized {
      runningState.stop()
    }

    // Needs to be outside the lock so we don't deadlock with `promptUpdaterThread`
    // trying to take the lock one last time to check running/paused status before exiting
    promptUpdaterThread.join()
  }

  def streams = streamManager.proxySystemStreams
}

private[mill] object PromptLogger {

  /**
   * Manages the paused/unpaused/stopped state of the prompt logger. Encapsulate in a separate
   * class because it has to maintain some invariants and ensure bookkeeping is properly done
   * when the paused state change, e.g. interrupting the prompt updater thread and clearing
   * the screen when the ticker is paused.
   */
  class RunningState(
      enableTicker: Boolean,
      promptUpdaterThreadInterrupt: () => Unit,
      clearOnPause: () => Unit,
      // Share the same synchronized lock as the parent PromptLogger, to simplify
      // reasoning about concurrency since it's not performance critical
      synchronizer: AnyRef
  ) {
    @volatile private var stopped0 = false
    @volatile private var paused0 = false
    def stopped = stopped0
    def paused = paused0
    def stop(): Unit = {
      stopped0 = true
      promptUpdaterThreadInterrupt()
    }

    def setPaused(prevPaused: Boolean, nextPaused: Boolean): Unit = synchronizer.synchronized {
      if (prevPaused != nextPaused) {
        paused0 = nextPaused
        if (nextPaused) {
          promptUpdaterThreadInterrupt()
          clearOnPause()
        }
      }
    }

    def withPromptPaused0[T](innerPaused: Boolean, t: => T): T = {
      if (!enableTicker) t
      else {
        val outerPaused = paused0
        try {
          setPaused(outerPaused, innerPaused)
          t
        } finally setPaused(innerPaused, outerPaused)
      }
    }
  }

  /**
   * Manages the system stream management logic necessary as part of the prompt. Intercepts
   * both stdout/stderr streams, ensuring the prompt is cleared before any output is printed,
   * and ensuring the prompt is re-printed after the streams have become quiescent.
   */
  private class StreamManager(
      enableTicker: Boolean,
      systemStreams0: SystemStreams,
      getCurrentPrompt: () => Array[Byte],
      interactive: () => Boolean,
      paused: () => Boolean,
      synchronizer: AnyRef
  ) {

    // We force both stdout and stderr streams into a single `Piped*Stream` pair via
    // `ProxyStream`, as we need to preserve the ordering of writes to each individual
    // stream, and also need to know when *both* streams are quiescent so that we can
    // print the prompt at the bottom
    val pipe = new PipeStreams()
    val proxyOut = new ProxyStream.Output(pipe.output, ProxyStream.OUT)
    val proxyErr: ProxyStream.Output = new ProxyStream.Output(pipe.output, ProxyStream.ERR)
    val proxySystemStreams = new SystemStreams(
      new PrintStream(proxyOut),
      new PrintStream(proxyErr),
      systemStreams0.in
    )

    def awaitPumperEmpty(): Unit = { while (pipe.input.available() != 0) Thread.sleep(2) }

    private var promptShown = true

    def writeCurrentPrompt(): Unit = {
      systemStreams0.err.write(getCurrentPrompt())
    }

    def refreshPrompt(): Unit = if (promptShown) writeCurrentPrompt()

    def clearOnPause(): Unit = {
      // Clear the prompt so the code in `t` has a blank terminal to work with
      systemStreams0.err.write(PromptLoggerUtil.clearScreenToEndBytes)
      systemStreams0.err.flush()
    }

    object pumper extends ProxyStream.Pumper(
          pipe.input,
          systemStreams0.out,
          systemStreams0.err,
          synchronizer
        ) {
      private var lastCharWritten = 0.toChar

      // Make sure we synchronize everywhere
      override def preRead(src: InputStream): Unit = synchronizer.synchronized {

        if (
          // Only bother printing the prompt after the streams have become quiescent
          // and there is no more stuff to print. This helps us to print the prompt on
          // every small write when most such prompts will get immediately over-written
          // by subsequent writes
          enableTicker && src.available() == 0 &&
          // Do not print the prompt when it is paused. Ideally stream redirecting would
          // prevent any writes from coming to this stream when paused, somehow writes
          // sometimes continue to come in, so just handle them gracefully.
          interactive() && !paused() &&
          // Only print the prompt when the last character that was written is a newline,
          // to ensure we don't cut off lines halfway
          lastCharWritten == '\n'
        ) {
          promptShown = true
          writeCurrentPrompt()
        }
      }

      override def write(dest: OutputStream, buf: Array[Byte], end: Int): Unit = {
        lastCharWritten = buf(end - 1).toChar
        if (interactive() && !paused() && promptShown) {
          promptShown = false
        }

        if (enableTicker && interactive()) {
          // Clear each line as they are drawn, rather than relying on clearing
          // the entire screen before each batch of writes, to try and reduce the
          // amount of terminal flickering in slow terminals (e.g. windows)
          // https://stackoverflow.com/questions/71452837/how-to-reduce-flicker-in-terminal-re-drawing
          dest.write(
            new String(buf, 0, end)
              .replaceAll("(\r\n|\n|\t)", AnsiNav.clearLine(0) + "$1")
              .getBytes
          )
        } else {
          dest.write(new String(buf, 0, end).getBytes)
        }
      }
    }

    val pumperThread = new Thread(pumper, "prompt-logger-stream-pumper-thread")
    pumperThread.start()

    def close(): Unit = {
      // Close the write side of the pipe first but do not close the read side, so
      // the `pumperThread` can continue reading remaining text in the pipe buffer
      // before terminating on its own
      ProxyStream.sendEnd(pipe.output)
      pipe.output.close()
      pumperThread.join()
    }
  }

  /**
   * Manages the state and logic necessary to render the status lines making up the prompt.
   * Needs to maintain state to implement debouncing logic to ensure the prompt changes
   * "smoothly" even as the underlying statuses may be rapidly changed during evaluation.
   */
  private class PromptLineState(
      titleText: String,
      startTimeMillis: Long,
      consoleDims: () => (Option[Int], Option[Int]),
      currentTimeMillis: () => Long,
      infoColor: fansi.Attrs
  ) {
    private val statuses = collection.mutable.SortedMap
      .empty[Seq[String], Status](PromptLoggerUtil.seqStringOrdering)

    private var headerPrefix = ""
    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently

    @volatile private var currentPromptBytes: Array[Byte] = Array[Byte]()

    def getCurrentPrompt() = currentPromptBytes

    def updatePrompt(ending: Boolean = false): Boolean = {
      val now = currentTimeMillis()
      for (k <- statuses.keySet) {
        val removedTime = statuses(k).beginTransitionTime
        if (statuses(k).next.isEmpty && (now - removedTime > statusRemovalRemoveDelayMillis)) {
          statuses.remove(k)
        }
      }

      // For the ending prompt, make sure we clear out all
      // the statuses to only show the header alone
      if (ending) statuses.clear()

      val (termWidth0, termHeight0) = consoleDims()
      val interactive = consoleDims()._1.nonEmpty
      // don't show prompt for non-interactive terminal
      val currentPromptLines = renderPrompt(
        termWidth0.getOrElse(defaultTermWidth),
        termHeight0.getOrElse(defaultTermHeight),
        now,
        startTimeMillis,
        if (headerPrefix.isEmpty) "" else s"[$headerPrefix]",
        titleText,
        statuses.toSeq.map { case (k, v) => (k.mkString("-"), v) },
        interactive = interactive,
        infoColor = infoColor
      )

      val oldPromptBytes = currentPromptBytes
      currentPromptBytes = renderPromptWrapped(currentPromptLines, interactive, ending).getBytes
      !java.util.Arrays.equals(oldPromptBytes, currentPromptBytes)
    }

    def clearStatuses(): Unit = { statuses.clear() }
    def setHeaderPrefix(s: String): Unit = { headerPrefix = s }

    def setDetail(key: Seq[String], detail: String): Unit = {
      statuses.updateWith(key)(_.map(se => se.copy(next = se.next.map(_.copy(detail = detail)))))
    }

    def setCurrent(key: Seq[String], sOpt: Option[String]): Unit = {

      val now = currentTimeMillis()
      def stillTransitioning(status: Status) = {
        status.beginTransitionTime + statusRemovalHideDelayMillis > now
      }
      val sOptEntry = sOpt.map(StatusEntry(_, now, ""))
      statuses.updateWith(key) {
        case None =>
          statuses.find { case (k, v) => v.next.isEmpty } match {
            case Some((reusableKey, reusableValue)) =>
              statuses.remove(reusableKey)
              Some(reusableValue.copy(next = sOptEntry))
            case None => Some(Status(sOptEntry, now, None))
          }

        case Some(existing) =>
          Some(
            // If still performing a transition, do not update the `prevTransitionTime`
            // since we do not want to delay the transition that is already in progress
            if (stillTransitioning(existing)) existing.copy(next = sOptEntry)
            else existing.copy(next = sOptEntry, beginTransitionTime = now, prev = existing.next)
          )
      }
    }
  }

}
