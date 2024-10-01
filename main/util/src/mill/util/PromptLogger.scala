package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import mill.util.PromptLoggerUtil.{
  Status,
  clearScreenToEndBytes,
  defaultTermHeight,
  defaultTermWidth,
  renderPrompt
}
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

  private val state = new State(
    titleText,
    systemStreams0,
    currentTimeMillis(),
    () => termDimensions,
    currentTimeMillis,
    infoColor
  )

  private val streamManager = new StreamManager(
    enableTicker,
    systemStreams0,
    () => state.currentPromptBytes,
    interactive = () => termDimensions._1.nonEmpty
  )

  @volatile var stopped = false
  @volatile var paused = false
  @volatile var pauseNoticed = false

  val promptUpdaterThread = new Thread(() =>
    while (!stopped) {
      val promptUpdateInterval =
        if (termDimensions._1.isDefined) promptUpdateIntervalMillis
        else nonInteractivePromptUpdateIntervalMillis

      Thread.sleep(promptUpdateInterval)

      if (!paused) {
        synchronized {
          // Double check the lock so if this was closed during the
          // `Thread.sleep`, we skip refreshing the prompt this loop
          if (!stopped) {
            readTerminalDims(terminfoPath).foreach(termDimensions = _)
            refreshPrompt()
          }
        }
      } else {
        pauseNoticed = true
      }
    }
  )

  def refreshPrompt(): Unit = state.refreshPrompt()
  if (enableTicker && autoUpdate) promptUpdaterThread.start()

  def info(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def error(s: String): Unit = synchronized { systemStreams.err.println(s) }

  override def setPromptLeftHeader(s: String): Unit = synchronized { state.updateGlobal(s) }
  override def clearPromptStatuses(): Unit = synchronized { state.clearStatuses() }
  override def removePromptLine(key: Seq[String]): Unit =
    synchronized { state.updateCurrent(key, None) }

  def ticker(s: String): Unit = ()
  override def setPromptDetail(key: Seq[String], s: String): Unit = {
    state.updateDetail(key, s)
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
      state.updateCurrent(key, Some(s"[${key.mkString("-")}] $message"))
      seenIdentifiers(key) = (verboseKeySuffix, message)
      super.setPromptLine(key.map(infoColor(_).toString()), verboseKeySuffix, message)

    }
  def debug(s: String): Unit = synchronized { if (debugEnabled) systemStreams.err.println(s) }

  override def rawOutputStream: PrintStream = systemStreams0.out

  override def close(): Unit = {
    synchronized {
      if (enableTicker) state.refreshPrompt(ending = true)
      streamManager.close()
      stopped = true
    }
    // Needs to be outside the lock so we don't deadlock with `promptUpdaterThread`
    // trying to take the lock one last time before exiting
    promptUpdaterThread.join()
  }

  def systemStreams = streamManager.systemStreams

  private[mill] override def withPromptPaused[T](t: => T): T = {
    pauseNoticed = false
    paused = true
    try {
      // After the prompt gets paused, wait until the `promptUpdaterThread` marks
      // `pauseNoticed = true`, so we can be sure it's done printing out prompt updates for
      // now and we can proceed with running `t` without any last updates slipping through
      while (!pauseNoticed) Thread.sleep(1)
      // Clear the prompt so the code in `t` has a blank terminal to work with
      systemStreams0.err.write(AnsiNav.clearScreen(0).getBytes)
      systemStreams0.err.flush()
      t

    } finally paused = false
  }

}

private[mill] object PromptLogger {

  private class StreamManager(
      enableTicker: Boolean,
      systemStreams0: SystemStreams,
      currentPromptBytes: () => Array[Byte],
      interactive: () => Boolean
  ) {

    // We force both stdout and stderr streams into a single `Piped*Stream` pair via
    // `ProxyStream`, as we need to preserve the ordering of writes to each individual
    // stream, and also need to know when *both* streams are quiescent so that we can
    // print the prompt at the bottom
    val pipe = new PipeStreams()
    val proxyOut = new ProxyStream.Output(pipe.output, ProxyStream.OUT)
    val proxyErr: ProxyStream.Output = new ProxyStream.Output(pipe.output, ProxyStream.ERR)
    val systemStreams = new SystemStreams(
      new PrintStream(proxyOut),
      new PrintStream(proxyErr),
      systemStreams0.in
    )

    def awaitPumperEmpty(): Unit = {
      while (pipe.input.available() != 0) Thread.sleep(10)
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
          if (interactive()) {
            systemStreams0.err.write(currentPromptBytes())
          }
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

    val pumperThread = new Thread(pumper)
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
  private class State(
      titleText: String,
      systemStreams0: SystemStreams,
      startTimeMillis: Long,
      consoleDims: () => (Option[Int], Option[Int]),
      currentTimeMillis: () => Long,
      infoColor: fansi.Attrs
  ) {
    private var lastRenderedPromptHash = 0

    private implicit def seqOrdering = new Ordering[Seq[String]] {
      def compare(xs: Seq[String], ys: Seq[String]): Int = {
        xs.lengthCompare(ys) match {
          case 0 =>
            val iter = xs.iterator.zip(ys)
            while (iter.nonEmpty) {
              val (x, y) = iter.next()
              if (x > y) return 1
              else if (y > x) return -1
            }
            return 0
          case n => n
        }
      }
    }
    private val statuses = collection.mutable.SortedMap.empty[Seq[String], Status]

    private var headerPrefix = ""
    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently

    @volatile var currentPromptBytes: Array[Byte] = Array[Byte]()

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
      // don't show prompt for non-interactive terminal
      val currentPromptLines = renderPrompt(
        termWidth0.getOrElse(defaultTermWidth),
        termHeight0.getOrElse(defaultTermHeight),
        now,
        startTimeMillis,
        s"[$headerPrefix]",
        titleText,
        statuses.toSeq.map { case (k, v) => (k.mkString("-"), v) },
        interactive = consoleDims()._1.nonEmpty,
        infoColor = infoColor,
        ending = ending
      )

      val currentPromptStr =
        if (termWidth0.isEmpty) currentPromptLines.mkString("\n") + "\n"
        else {
          // For the ending prompt, leave the cursor at the bottom on a new line rather than
          // scrolling back left/up. We do not want further output to overwrite the header as
          // it will no longer re-render
          val backUp =
            if (ending) "\n"
            else AnsiNav.left(9999) + AnsiNav.up(currentPromptLines.length - 1)

          AnsiNav.clearScreen(0) +
            currentPromptLines.mkString("\n") +
            backUp
        }

      currentPromptBytes = currentPromptStr.getBytes

    }

    def clearStatuses(): Unit = synchronized { statuses.clear() }
    def updateGlobal(s: String): Unit = synchronized { headerPrefix = s }

    def updateDetail(key: Seq[String], detail: String): Unit = synchronized {
      statuses.updateWith(key)(_.map(se => se.copy(next = se.next.map(_.copy(detail = detail)))))
    }

    def updateCurrent(key: Seq[String], sOpt: Option[String]): Unit = synchronized {

      val now = currentTimeMillis()
      def stillTransitioning(status: Status) = {
        status.beginTransitionTime + statusRemovalHideDelayMillis > now
      }
      val sOptEntry = sOpt.map(StatusEntry(_, now, ""))
      statuses.updateWith(key) {
        case None =>
          statuses.find { case (k, v) => v.next.isEmpty && stillTransitioning(v) } match {
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
            else {
              existing.copy(
                next = sOptEntry,
                beginTransitionTime = now,
                prev = existing.next
              )
            }
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
        systemStreams0.err.write(currentPromptBytes)
      }
    }
  }

}
