package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream

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

  private var termWidth: Option[Int] = None
  private var termHeight: Option[Int] = None

  for((w, h) <- readTerminalDims(terminfoPath)) {
    termWidth = w
    termHeight = h
  }

  private val state = new State(
    titleText,
    enableTicker,
    systemStreams0,
    System.currentTimeMillis(),
    () => (termWidth, termHeight)
  )

  private val streams = new Streams(enableTicker, systemStreams0, () => state.currentPromptBytes)

  @volatile var stopped = false
  @volatile var paused = false

  val promptUpdaterThread = new Thread(() =>
    while (!stopped) {
      Thread.sleep(
        if (termWidth.isDefined) promptUpdateIntervalMillis
        else nonInteractivePromptUpdateIntervalMillis
      )

      if (!paused) {
        synchronized {
          for((w, h) <- readTerminalDims(terminfoPath)) {
            termWidth = w
            termHeight = h
          }
          state.refreshPrompt()
        }
      }
    }
  )

  if (enableTicker) promptUpdaterThread.start()

  override def withPaused[T](t: => T): T = {
    paused = true
    try t
    finally paused = false
  }

  def info(s: String): Unit = synchronized { systemStreams.err.println(s) }

  def error(s: String): Unit = synchronized { systemStreams.err.println(s) }

  override def globalTicker(s: String): Unit = synchronized { state.updateGlobal(s) }

  override def endTicker(): Unit = synchronized { state.updateCurrent(None) }

  def ticker(s: String): Unit = synchronized { state.updateCurrent(Some(s)) }

  def debug(s: String): Unit = synchronized { if (debugEnabled) systemStreams.err.println(s) }

  override def rawOutputStream: PrintStream = systemStreams0.out

  override def close(): Unit = {
    streams.close()
    state.refreshPrompt(ending = true)
    stopped = true
  }

  def systemStreams = streams.systemStreams
}

private object MultilinePromptLogger {

  private val defaultTermWidth = 119
  private val defaultTermHeight = 50
  /**
   * How often to update the multiline status prompt on the terminal.
   * Too frequent is bad because it causes a lot of visual noise,
   * but too infrequent results in latency. 10 times per second seems reasonable
   */
  private val promptUpdateIntervalMillis = 100

  /**
   * How often to update the multiline status prompt in noninteractive scenarios,
   * e.g. background job logs or piped to a log file. Much less frequent than the
   * interactive scenario because we cannot rely on ANSI codes to over-write the
   * previous prompt, so we have to be a lot more conservative to avoid spamming
   * the logs
   */
  private val nonInteractivePromptUpdateIntervalMillis = 10000

  /**
   * Add some extra latency delay to the process of removing an entry from the status
   * prompt entirely, because removing an entry changes the height of the prompt, which
   * is even more distracting than changing the contents of a line, so we want to minimize
   * those occurrences even further.
   */
  val statusRemovalHideDelayMillis = 250

  /**
   * How long to wait before actually removing the blank line left by a removed status
   * and reducing the height of the prompt.
   */
  val statusRemovalRemoveDelayMillis = 2000

  private[mill] case class Status(startTimeMillis: Long, text: String, var removedTimeMillis: Long)

  private val clearScreenToEndBytes: Array[Byte] = AnsiNav.clearScreen(0).getBytes

  private class Streams(enableTicker: Boolean,
                        systemStreams0: SystemStreams,
                        currentPromptBytes: () => Array[Byte]){

    // We force both stdout and stderr streams into a single `Piped*Stream` pair via
    // `ProxyStream`, as we need to preserve the ordering of writes to each individual
    // stream, and also need to know when *both* streams are quiescent so that we can
    // print the prompt at the bottom
    val pipeIn = new PipedInputStream()
    val pipeOut = new PipedOutputStream()
    pipeIn.available()
    pipeIn.connect(pipeOut)
    val proxyOut = new ProxyStream.Output(pipeOut, ProxyStream.OUT)
    val proxyErr = new ProxyStream.Output(pipeOut, ProxyStream.ERR)
    val systemStreams = new SystemStreams(
      new PrintStream(proxyOut),
      new PrintStream(proxyErr),
      systemStreams0.in
    )

    val pumper = new ProxyStream.Pumper(pipeIn, systemStreams0.out, systemStreams0.err){
      object PumperState extends Enumeration{
        val init, prompt, cleared = Value
      }
      var pumperState = PumperState.init
      override def preRead(src: InputStream): Unit = {
        // Only bother printing the propmt after the streams have become quiescent
        // and there is no more stuff to print. This helps us printing the prompt on
        // every small write when most such prompts will get immediately over-written
        // by subsequent writes
        if (enableTicker && src.available() == 0){
          systemStreams0.err.write(currentPromptBytes())
          pumperState = PumperState.prompt
        }
      }

      override def preWrite(): Unit = {
        // Before any write, make sure we clear the terminal of any prompt that was
        // written earlier and not yet cleared, so the following output can be written
        // to a clean section of the terminal
        if (pumperState != PumperState.cleared) systemStreams0.err.write(clearScreenToEndBytes)
        pumperState = PumperState.cleared
      }
    }
    val pumperThread = new Thread(pumper)
    pumperThread.start()

    def close() = {
      pipeIn.close()
      pipeOut.close()
    }
  }
  private class State(
      titleText: String,
      enableTicker: Boolean,
      systemStreams0: SystemStreams,
      startTimeMillis: Long,
      consoleDims: () => (Option[Int], Option[Int])
  ) {
    private val statuses = collection.mutable.SortedMap.empty[Int, Status]

    private var headerPrefix = ""
    // Pre-compute the prelude and current prompt as byte arrays so that
    // writing them out is fast, since they get written out very frequently

    @volatile var currentPromptBytes: Array[Byte] = Array[Byte]()

    private def updatePromptBytes(ending: Boolean = false) = {
      val now = System.currentTimeMillis()
      for (k <- statuses.keySet) {
        val removedTime = statuses(k).removedTimeMillis
        if (now - removedTime > statusRemovalRemoveDelayMillis) {
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
        headerPrefix,
        titleText,
        statuses
      )
      // For the ending prompt, leave the cursor at the bottom rather than scrolling back up.
      // We do not want further output to overwrite the header as it will no longer re-render
      val backUp = if (ending) "" else AnsiNav.up(currentPromptLines.length)

      val currentPromptStr =
        if (termWidth0.isEmpty) currentPromptLines.mkString("\n")
        else {
          AnsiNav.clearScreen(0) +
            currentPromptLines.mkString("\n") + "\n" +
            backUp
        }

      currentPromptBytes = currentPromptStr.getBytes
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

    def refreshPrompt(ending: Boolean = false): Unit = synchronized {
      updatePromptBytes(ending)
      if (enableTicker) systemStreams0.err.write(currentPromptBytes)
    }
  }

  private def renderSeconds(millis: Long) = (millis / 1000).toInt match {
    case 0 => ""
    case n => s"${n}s"
  }

  def readTerminalDims(terminfoPath: os.Path) = {
    try {
      val s"$termWidth0 $termHeight0" = os.read(terminfoPath)
      Some(
        Tuple2(
          termWidth0.toInt match {
            case -1 | 0 => None
            case n => Some(n)
          },
          termHeight0.toInt match {
            case -1 | 0 => None
            case n => Some(n)
          }
        )
      )
    }catch{case e => None}
  }

  def renderPrompt(
      consoleWidth: Int,
      consoleHeight: Int,
      now: Long,
      startTimeMillis: Long,
      headerPrefix: String,
      titleText: String,
      statuses: collection.SortedMap[Int, Status]
  ): List[String] = {
    // -1 to leave a bit of buffer
    val maxWidth = consoleWidth - 1
    // -2 to account for 1 line header and 1 line `more threads`
    val maxHeight = math.max(1, consoleHeight / 3 - 2)
    val headerSuffix = renderSeconds(now - startTimeMillis)

    val header = renderHeader(headerPrefix, titleText, headerSuffix, maxWidth)
    val body0 = statuses
      .collect {
        case (threadId, status) =>
          if (now - status.removedTimeMillis > statusRemovalHideDelayMillis) ""
          else splitShorten(
            status.text + " " + renderSeconds(now - status.startTimeMillis),
            maxWidth
          )
      }
      .toList
      .sortBy(_.isEmpty)

    val body =
      if (body0.count(_.nonEmpty) <= maxHeight) body0.take(maxHeight)
      else body0.take(maxHeight - 1) ++ Seq(s"... and ${body0.length - maxHeight + 1} more threads")

    header :: body
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
    val maxTitleLength =
      maxWidth - math.max(headerPrefixStr.length, headerSuffixStr.length) * 2 - 12
    val shortenedTitle = splitShorten(titleText, maxTitleLength)

    // +2 to offset the title a bit to the right so it looks centered, as the `headerPrefixStr`
    // is usually longer than `headerSuffixStr`. We use a fixed offset rather than dynamically
    // offsetting by `headerPrefixStr.length` to prevent the title from shifting left and right
    // as the `headerPrefixStr` changes, even at the expense of it not being perfectly centered.
    val leftDivider = "=" * ((maxWidth / 2) - (titleText.length / 2) - headerPrefixStr.length + 2)
    val rightDivider =
      "=" * (maxWidth - headerPrefixStr.length - leftDivider.length - shortenedTitle.length - headerSuffixStr.length)
    val headerString =
      headerPrefixStr + leftDivider + shortenedTitle + rightDivider + headerSuffixStr
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
