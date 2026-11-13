package mill.javalib.internal

/**
 * Maps sources wrapped for the `-Ymagic-offset-header` option of the Scala 3 compiler
 * back to their original sources.
 *
 * With `-Ymagic-offset-header:<header>`, the compiler looks for the first line of a source
 * that reads `///<header>:<path of the original source>`. The code that follows that line
 * comes from the original source, and the compiler reports positions in it as positions in
 * the original source. So the code from the original source has to keep its offsets,
 * which means only length-preserving edits can be made to it (like Mill commenting out
 * the package clause of build files), and it has the same lines as the original source.
 * Some more code can follow it in the wrapped source.
 */
private[mill] object WrappedSources {

  private val optionName = "-Ymagic-offset-header"

  /**
   * The header passed to the compiler via `-Ymagic-offset-header`, if any
   *
   * Like the compiler, accepts both `-Ymagic-offset-header:<header>` and
   * `-Ymagic-offset-header <header>`, the last occurrence winning.
   */
  def magicOffsetHeader(scalacOptions: Seq[String]): Option[String] = {
    val nextOptions = scalacOptions.drop(1).map(Some(_)) :+ None
    scalacOptions.zip(nextOptions)
      .collect {
        case (s"-Ymagic-offset-header:$header", _) => header
        case (`optionName`, Some(header)) => header
      }
      .lastOption
      .filter(_.nonEmpty)
  }

  /**
   * Where the code from the original source starts in a wrapped source
   *
   * @param originalSource the original source
   * @param offset offset in the wrapped source of the first character of the original code
   * @param lineIdx zero-based index of the line of the wrapped source the original code starts on
   */
  final case class UserCodeStart(originalSource: os.Path, offset: Int, lineIdx: Int)

  /**
   * Finds where the code from the original source starts in a wrapped source
   *
   * @param header the header passed to the compiler via `-Ymagic-offset-header`
   */
  def userCodeStart(wrappedContent: String, header: String): Option[UserCodeStart] = {
    val marker = s"///$header:"
    val markerIdx =
      if (wrappedContent.startsWith(marker)) 0
      else wrappedContent.indexOf("\n" + marker) match {
        case -1 => -1
        case n => n + 1
      }
    val markerLineEnd =
      if (markerIdx < 0) -1
      else wrappedContent.indexOf('\n', markerIdx)
    if (markerLineEnd < 0) None
    else {
      val pathStr = wrappedContent
        .substring(markerIdx + marker.length, markerLineEnd)
        .trim
      val pathOpt =
        try Some(os.Path(pathStr))
        catch { case _: IllegalArgumentException => None }
      pathOpt.map { path =>
        val offset = markerLineEnd + 1
        UserCodeStart(path, offset, wrappedContent.iterator.take(offset).count(_ == '\n'))
      }
    }
  }

  /**
   * Splits a wrapped source into the code before and after the code from the original
   * source, given the length of the original source
   *
   * @param header the header passed to the compiler via `-Ymagic-offset-header`
   */
  def wrappers(
      wrappedContent: String,
      header: String,
      originalLength: Int
  ): Option[(top: String, bottom: String)] =
    userCodeStart(wrappedContent, header).flatMap { start =>
      val userCodeEnd = start.offset + originalLength
      Option.when(userCodeEnd <= wrappedContent.length) {
        (
          top = wrappedContent.take(start.offset),
          bottom = wrappedContent.drop(userCodeEnd)
        )
      }
    }
}
