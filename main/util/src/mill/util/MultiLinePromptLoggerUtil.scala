package mill.util

private object MultilinePromptLoggerUtil {

  private[mill] val defaultTermWidth = 119
  private[mill] val defaultTermHeight = 50

  /**
   * How often to update the multiline status prompt on the terminal.
   * Too frequent is bad because it causes a lot of visual noise,
   * but too infrequent results in latency. 10 times per second seems reasonable
   */
  private[mill] val promptUpdateIntervalMillis = 100

  /**
   * How often to update the multiline status prompt in noninteractive scenarios,
   * e.g. background job logs or piped to a log file. Much less frequent than the
   * interactive scenario because we cannot rely on ANSI codes to over-write the
   * previous prompt, so we have to be a lot more conservative to avoid spamming
   * the logs, but we still want to print it occasionally so people can debug stuck
   * background or CI jobs and see what tasks it is running when stuck
   */
  private[mill] val nonInteractivePromptUpdateIntervalMillis = 60000

  /**
   * Add some extra latency delay to the process of removing an entry from the status
   * prompt entirely, because removing an entry changes the height of the prompt, which
   * is even more distracting than changing the contents of a line, so we want to minimize
   * those occurrences even further.
   */
  val statusRemovalHideDelayMillis = 500

  /**
   * How long to wait before actually removing the blank line left by a removed status
   * and reducing the height of the prompt. Having the prompt change height is even more
   * distracting than having entries in the prompt disappear, so give it a longer timeout
   * so it happens less.
   */
  val statusRemovalRemoveDelayMillis = 2000


  private[mill] case class Status(text: String, startTimeMillis: Long, removedTimeMillis: Long){
    def shouldRender(now: Long): Option[String] = {
      if (removedTimeMillis == Long.MaxValue) {
        Option.when(now - startTimeMillis > statusRemovalHideDelayMillis)(text)
      } else{
        if (now - removedTimeMillis > statusRemovalRemoveDelayMillis) None
        else Option.when(now - startTimeMillis > statusRemovalHideDelayMillis)(text)
      }
    }
  }

  private[mill] val clearScreenToEndBytes: Array[Byte] = AnsiNav.clearScreen(0).getBytes

  private def renderSeconds(millis: Long) = (millis / 1000).toInt match {
    case 0 => ""
    case n => s"${n}s"
  }

  def readTerminalDims(terminfoPath: os.Path): Option[(Option[Int], Option[Int])] = {
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
    } catch { case e => None }
  }

  def renderPrompt(
      consoleWidth: Int,
      consoleHeight: Int,
      now: Long,
      startTimeMillis: Long,
      headerPrefix: String,
      titleText: String,
      statuses: collection.SortedMap[Int, Status],
      interactive: Boolean,
      ending: Boolean = false
  ): List[String] = {
    // -1 to leave a bit of buffer
    val maxWidth = consoleWidth - 1
    // -1 to account for header
    val maxHeight = math.max(1, consoleHeight / 3 - 1)
    val headerSuffix = renderSeconds(now - startTimeMillis)

    val header = renderHeader(headerPrefix, titleText, headerSuffix, maxWidth, ending, interactive)
    val body0 = statuses
      .map {
        case (threadId, status) =>
          if (now - status.removedTimeMillis > statusRemovalHideDelayMillis) ""
          else splitShorten(
            status.text + " " + renderSeconds(now - status.startTimeMillis),
            maxWidth
          )
      }
      // For non-interactive jobs, we do not need to preserve the height of the prompt
      // between renderings, since consecutive prompts do not appear at the same place
      // in the log file. Thus we can aggressively remove all blank spacer lines
      .filter(_.nonEmpty || interactive)
      .toList
      // Sort alphabetically because the `#nn` prefix is part of the string, and then
      // put all empty strings last since those are less important and can be ignored
      .sortBy(x => (x.isEmpty, x))

    val nonEmptyBodyCount = body0.count(_.nonEmpty)
    val body =
      if (nonEmptyBodyCount <= maxHeight) body0.take(maxHeight)
      else body0.take(maxHeight - 1) ++ Seq(
        s"... and ${nonEmptyBodyCount - maxHeight + 1} more threads"
      )

    // For non-interactive jobs, the prompt won't be at the bottom of the terminal but
    // will instead be in the middle of a big log file with logs above and below, so we
    // need some kind of footer to tell the reader when the prompt ends and logs begin
    val footer = Option.when(!interactive)("=" * maxWidth).toList

    header :: body ::: footer
  }

  def renderHeader(
      headerPrefix0: String,
      titleText0: String,
      headerSuffix0: String,
      maxWidth: Int,
      ending: Boolean = false,
      interactive: Boolean = true
  ): String = {
    val headerPrefixStr = if (!interactive || ending) s"$headerPrefix0 " else s"  $headerPrefix0 "
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
