package mill.internal

import java.io.OutputStream
import scala.annotation.switch

private object PromptLoggerUtil {

  val defaultTermWidth = 99
  val defaultTermHeight = 25

  /**
   * How often to update the multiline status prompt on the terminal.
   * Too frequent is bad because it causes a lot of visual noise,
   * but too infrequent results in latency. 10 times per second seems reasonable
   */
  val promptUpdateIntervalMillis = 100

  /**
   * How often to update the multiline status prompt in non-interactive scenarios,
   * e.g. background job logs or piped to a log file. Much less frequent than the
   * interactive scenario because we cannot rely on ANSI codes to over-write the
   * previous prompt, so we have to be a lot more conservative to avoid spamming
   * the logs, but we still want to print it occasionally so people can debug stuck
   * background or CI jobs and see what tasks it is running when stuck
   */
  val nonInteractivePromptUpdateIntervalMillis = 60000

  /**
   * Add some extra latency delay to the process of removing an entry from the status
   * prompt entirely, because removing an entry changes the height of the prompt, which
   * is even more distracting than changing the contents of a line, so we want to minimize
   * those occurrences even further.
   */
  val statusRemovalHideDelayMillis = 250

  /**
   * How long to wait before actually removing the blank line left by a removed status
   * and reducing the height of the prompt. Having the prompt change height is even more
   * distracting than having entries in the prompt disappear, so give it a longer timeout
   * so it happens less.
   */
  val statusRemovalRemoveDelayMillis = 2000

  case class StatusEntry(text: fansi.Str, startTimeMillis: Long, detail: String = "")

  /**
   * Represents a line in the prompt. Stores up to two separate [[StatusEntry]]s, because
   * we want to buffer up status transitions to debounce them. Which status entry is currently
   * shown depends on the [[beginTransitionTime]] and other heuristics
   */
  case class Status(
      next: Option[StatusEntry],
      beginTransitionTime: Long,
      prev: Option[StatusEntry]
  )

  /**
   * Starting a line with `clearScreen` mucks up tab stops in iTerm, so make sure we navigate `up`
   * and down via `\n` to have a "fresh" line. This only should get called to clear the prompt, so
   * the cursor is already at the left-most column, which '\n' will not change.
   */
  val clearScreenToEndBytes: Array[Byte] =
    (AnsiNav.clearScreen(0) + AnsiNav.up(1) + "\n").getBytes

  def spaceNonEmpty(s: String) = if (s.isEmpty) "" else s" $s"

  def readTerminalDims(terminfoPath: os.Path): Option[(Option[Int], Option[Int])] = {
    try {
      val s"$termWidth0 $termHeight0" = os.read(terminfoPath).runtimeChecked
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
    } catch { case _ => None }
  }

  def renderPrompt(
      consoleWidth: Int,
      consoleHeight: Int,
      now: Long,
      startTimeMillis: Long,
      headerPrefix: fansi.Str,
      titleText: fansi.Str,
      statuses: Iterable[(String, Status)],
      interactive: Boolean,
      infoColor: fansi.Attrs
  ): List[fansi.Str] = {
    // -1 to leave a bit of buffer
    val maxWidth = consoleWidth - 1
    // -1 to account for header
    val maxHeight = math.max(1, consoleHeight / 3 - 1)
    val headerSuffix = mill.api.internal.Util.renderSecondsSuffix(now - startTimeMillis)

    val header = renderHeader(headerPrefix, titleText, headerSuffix, maxWidth)

    val body0 = statuses
      .flatMap {
        case (_, status) =>
          // For statuses that have completed transitioning from Some to None, continue
          // rendering them as an empty line for `statusRemovalRemoveDelayMillis` to try
          // and maintain prompt height and stop it from bouncing up and down
          if (
            status.prev.nonEmpty &&
            status.next.isEmpty &&
            status.beginTransitionTime + statusRemovalHideDelayMillis < now &&
            status.beginTransitionTime > now - statusRemovalRemoveDelayMillis
          ) {
            Some(fansi.Str(""))
          } else {
            val textOpt = if (status.beginTransitionTime + statusRemovalHideDelayMillis < now)
              status.next
            else status.prev
            textOpt.map { t =>
              val seconds = mill.api.internal.Util.renderSecondsSuffix(now - t.startTimeMillis)
              val mainText = splitShorten(t.text ++ seconds, maxWidth)

              val detail = splitShorten(spaceNonEmpty(t.detail), maxWidth - mainText.length)

              mainText ++ detail
            }
          }
      }
      // For non-interactive jobs, we do not need to preserve the height of the prompt
      // between renderings, since consecutive prompts do not appear at the same place
      // in the log file. Thus, we can aggressively remove all blank spacer lines
      .filter(_.length > 0 || interactive)
      .toList
      // Sort alphabetically because the `#nn` prefix is part of the string, and then
      // put all empty strings last since those are less important and can be ignored
      .sortBy(x => x.length == 0)

    val nonEmptyBodyCount = body0.count(_.length > 0)
    val body: List[fansi.Str] =
      if (nonEmptyBodyCount <= maxHeight) body0.take(maxHeight)
      else body0.take(maxHeight - 1) ++ Seq(
        s"... and ${nonEmptyBodyCount - maxHeight + 1} more threads"
      )

    header :: body
  }

  // Wrap the prompt in the necessary clear-screens/newlines/move-cursors
  // according to whether it is interactive or ending
  def renderPromptWrapped(
      currentPromptLines: Seq[String],
      interactive: Boolean
  ): String = {
    if (!interactive) currentPromptLines.mkString("\n") + "\n"
    else currentPromptLines.map(_ + AnsiNav.clearLine(0)).mkString("\n") + "\n"
  }

  def renderHeader(
      headerPrefix0: fansi.Str,
      titleText: fansi.Str,
      headerSuffixStr: fansi.Str,
      maxWidth: Int
  ): fansi.Str = {
    val headerPrefixStr = fansi.Str(if (headerPrefix0.length == 0) "" else s"$headerPrefix0 ")
    val maxTitleLength = maxWidth - headerPrefixStr.length - headerSuffixStr.length
    val shortenedTitle = splitShorten(titleText, maxTitleLength)

    val headerString = headerPrefixStr ++ shortenedTitle ++ headerSuffixStr
    splitShorten(headerString, maxWidth)
  }

  def splitShorten(s: fansi.Str, maxLength: Int): fansi.Str = {
    if (s.length <= maxLength) s
    else if (maxLength <= 3) fansi.Str("...".take(maxLength))
    else {
      val ellipses = "..."
      val nonEllipsesLength = maxLength - ellipses.length
      val halfWidth = nonEllipsesLength / 2
      val halfWidth2 = nonEllipsesLength - halfWidth

      s.substring(0, halfWidth2) ++
        fansi.Str(ellipses) ++
        s.substring(s.length - halfWidth, s.length)
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

  val seqStringOrdering = new Ordering[Seq[String]] {
    def compare(xs: Seq[String], ys: Seq[String]): Int = {
      val iter = xs.iterator.zip(ys)
      while (iter.nonEmpty) {
        val (x, y) = iter.next()
        if (x > y) return 1
        else if (y > x) return -1
      }

      return xs.lengthCompare(ys)
    }
  }

  def streamToPrependNewlines(
      dest: OutputStream,
      buf: Array[Byte],
      end: Int,
      prepended: Array[Byte]
  ) = {
    var last = 0
    var i = 0
    while (i < end) {
      (buf(i): @switch) match {
        case '\r' =>
          if (i + 1 < end && buf(i + 1) == '\n') {
            // \r\n case: prepend before the \r\n sequence
            dest.write(buf, last, i - last)
            dest.write(prepended)
            last = i
            i += 2
          } else {
            // Standalone \r: prepend before the \r to support progress output
            // that uses \r to overwrite the current line
            dest.write(buf, last, i - last)
            dest.write(prepended)
            last = i
            i += 1
          }
        case '\n' | '\t' =>
          dest.write(buf, last, i - last)
          dest.write(prepended)
          last = i
          i += 1
        case _ => i += 1
      }
    }
    dest.write(buf, last, end - last)
  }
}
