package mill.internal

private[mill] object Util {
  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }

  /**
   * Format a failed count as a string to be used in status messages.
   * Returns ", N failed" if count > 0, otherwise an empty string.
   */
  def formatFailedCount(count: Int): String = {
    if (count > 0) s", $count failed" else ""
  }
}
