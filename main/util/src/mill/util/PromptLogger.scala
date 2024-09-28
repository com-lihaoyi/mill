package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import pprint.Util.literalize

import java.io._
import PromptLoggerUtil._

private[mill] class PromptLogger(
    override val colored: Boolean,
    override val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    systemStreams0: SystemStreams,
    override val debugEnabled: Boolean,
    titleText: String,
    terminfoPath: os.Path,
    currentTimeMillis: () => Long,
    autoUpdate: Boolean = true
) extends ColorLogger with AutoCloseable {
  override def toString: String = s"PromptLogger(${literalize(titleText)})"
  import PromptLogger._

  private var termDimensions: (Option[Int], Option[Int]) = (None, None)

  readTerminalDims(terminfoPath).foreach(termDimensions = _)

  private object promptLineState extends PromptLineState(
        titleText,
        systemStreams0,
        currentTimeMillis(),
        () => termDimensions,
        currentTimeMillis,
        infoColor
      )

  private object streamManager extends StreamManager(
        enableTicker,
        systemStreams0,
        () => promptLineState.writeCurrentPrompt(),
        interactive = () => termDimensions._1.nonEmpty
      )

  private object runningState extends RunningState(
        enableTicker,
        () => promptUpdaterThread.interrupt(),
        clearOnPause = () => {
          // Clear the prompt so the code in `t` has a blank terminal to work with
          systemStreams0.err.write(AnsiNav.clearScreen(0).getBytes)
          systemStreams0.err.flush()
        },
        this
      )

  val promptUpdaterThread = new Thread(
    () =>
      while (!runningState.stopped) {
        val promptUpdateInterval =
          if (termDimensions._1.isDefined) promptUpdateIntervalMillis
          else nonInteractivePromptUpdateIntervalMillis

        try Thread.sleep(promptUpdateInterval)
        catch { case e: InterruptedException => /*do nothing*/ }

        readTerminalDims(terminfoPath).foreach(termDimensions = _)

        synchronized {
          if (!runningState.paused && !runningState.stopped) {
            refreshPrompt()
          }
        }
      },
    "prompt-logger-updater-thread"
  )

  def refreshPrompt(): Unit = promptLineState.refreshPrompt()
  if (enableTicker && autoUpdate) promptUpdaterThread.start()

  def info(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def error(s: String): Unit = synchronized { systemStreams.err.println(s) }

  override def setPromptHeaderPrefix(s: String): Unit =
    synchronized { promptLineState.setHeaderPrefix(s) }
  override def clearPromptStatuses(): Unit = synchronized { promptLineState.clearStatuses() }
  override def removePromptLine(key: Seq[String]): Unit = synchronized {
    promptLineState.setCurrent(key, None)
  }

  def ticker(s: String): Unit = ()
  override def setPromptDetail(key: Seq[String], s: String): Unit = synchronized {
    promptLineState.setDetail(key, s)
  }

  override def reportKey(key: Seq[String]): Unit = synchronized {
    if (!reportedIdentifiers(key)) {
      reportedIdentifiers.add(key)
      for ((verboseKeySuffix, message) <- seenIdentifiers.get(key)) {
        if (enableTicker) {
          systemStreams.err.println(infoColor(s"[${key.mkString("-")}$verboseKeySuffix] $message"))
        }
      }
    }
  }

  def streamsAwaitPumperEmpty(): Unit = streamManager.awaitPumperEmpty()
  private val seenIdentifiers = collection.mutable.Map.empty[Seq[String], (String, String)]
  private val reportedIdentifiers = collection.mutable.Set.empty[Seq[String]]
  override def setPromptLine(key: Seq[String], verboseKeySuffix: String, message: String): Unit =
    synchronized {
      promptLineState.setCurrent(key, Some(s"[${key.mkString("-")}] $message"))
      seenIdentifiers(key) = (verboseKeySuffix, message)
    }

  def debug(s: String): Unit = synchronized { if (debugEnabled) systemStreams.err.println(s) }

  override def rawOutputStream: PrintStream = systemStreams0.out

  override def close(): Unit = {
    synchronized {
      if (enableTicker) promptLineState.refreshPrompt(ending = true)
      streamManager.close()
      runningState.stop()
    }

    // Needs to be outside the lock so we don't deadlock with `promptUpdaterThread`
    // trying to take the lock one last time to check running/paused status before exiting
    promptUpdaterThread.join()
  }

  def systemStreams = streamManager.proxySystemStreams

  private[mill] override def withPromptPaused[T](t: => T): T =
    runningState.withPromptPaused0(true, t)
  private[mill] override def withPromptUnpaused[T](t: => T): T =
    runningState.withPromptPaused0(false, t)
}

private[mill] object PromptLogger {

  /**
   * Manages the paused/unpaused/stopped state of the prompt logger. Encapsulate in a separate
   * class because it has to maintain some invariants and ensure book-keeping is properly done
   * when the paused state change, e.g. interrupting the prompt updater thread, waiting for
   * `pauseNoticed` to fire and clearing the screen when the ticker is paused.
   */
  class RunningState(
      enableTicker: Boolean,
      promptUpdaterThreadInterrupt: () => Unit,
      clearOnPause: () => Unit,
      // Share the same synchronized lock as the parent PromptLogger, to simplify
      // reasoning about concurrency since it's not performance critical
      synchronizer: PromptLogger
  ) {
    @volatile private var stopped0 = false
    @volatile private var paused0 = false
    def stopped = stopped0
    def paused = paused0
    def stop(): Unit = synchronizer.synchronized {
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
      writeCurrentPrompt: () => Unit,
      interactive: () => Boolean
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

    def awaitPumperEmpty(): Unit = {
      while (pipe.input.available() != 0) Thread.sleep(2)
    }
    object pumper extends ProxyStream.Pumper(pipe.input, systemStreams0.out, systemStreams0.err) {
      object PumperState extends Enumeration {
        val init, prompt, cleared = Value
      }
      var pumperState = PumperState.init
      override def preRead(src: InputStream): Unit = {
        // Only bother printing the propmt after the streams have become quiescent
        // and there is no more stuff to print. This helps us printing the prompt on
        // every small write when most such prompts will get immediately over-written
        // by subsequent writes
        if (enableTicker && src.available() == 0) {
          if (interactive()) writeCurrentPrompt()
          pumperState = PumperState.prompt
        }
      }

      override def preWrite(): Unit = {
        // Before any write, make sure we clear the terminal of any prompt that was
        // written earlier and not yet cleared, so the following output can be written
        // to a clean section of the terminal
        if (interactive() && pumperState != PumperState.cleared)
          systemStreams0.err.write(clearScreenToEndBytes)
        pumperState = PumperState.cleared
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
      systemStreams0: SystemStreams,
      startTimeMillis: Long,
      consoleDims: () => (Option[Int], Option[Int]),
      currentTimeMillis: () => Long,
      infoColor: fansi.Attrs
  ) {
    private var lastRenderedPromptHash = 0

    private val statuses = collection.mutable.SortedMap
      .empty[Seq[String], Status](PromptLoggerUtil.seqStringOrdering)

    private var headerPrefix = ""
    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently

    @volatile private var currentPromptBytes: Array[Byte] = Array[Byte]()

    def writeCurrentPrompt(): Unit = systemStreams0.err.write(currentPromptBytes)
    private def updatePromptBytes(ending: Boolean = false) = {
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
        s"[$headerPrefix]",
        titleText,
        statuses.toSeq.map { case (k, v) => (k.mkString("-"), v) },
        interactive = interactive,
        infoColor = infoColor,
        ending = ending
      )

      currentPromptBytes = renderPromptWrapped(currentPromptLines, interactive, ending).getBytes

    }

    def clearStatuses(): Unit = synchronized { statuses.clear() }
    def setHeaderPrefix(s: String): Unit = synchronized { headerPrefix = s }

    def setDetail(key: Seq[String], detail: String): Unit = synchronized {
      statuses.updateWith(key)(_.map(se => se.copy(next = se.next.map(_.copy(detail = detail)))))
    }

    def setCurrent(key: Seq[String], sOpt: Option[String]): Unit = synchronized {

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

    def refreshPrompt(ending: Boolean = false): Unit = synchronized {

      // For non-interactive jobs, we only want to print the new prompt if the contents
      // differs from the previous prompt, since the prompts do not overwrite each other
      // in log files and printing large numbers of identical prompts is spammy and useless

      lazy val statusesHashCode = statuses.hashCode
      if (consoleDims()._1.nonEmpty || statusesHashCode != lastRenderedPromptHash) {
        lastRenderedPromptHash = statusesHashCode
        updatePromptBytes(ending)
        writeCurrentPrompt()
      }
    }
  }

}
