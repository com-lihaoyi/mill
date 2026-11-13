package mill.javalib.internal

import mill.constants.CodeGenConstants

/**
 * Maps the sources Mill generates out of build files back to the build files.
 *
 * A generated source consists in a header ending with a line starting with
 * [[CodeGenConstants.userCodeStartMarker]] and followed by the path of the build file,
 * then the user code, then a footer. Code generation only makes length-preserving edits
 * to the user code (`-Ymagic-offset-header` relies on offsets), so the user code has the
 * same length, and the same lines, as the build file.
 */
private[mill] object GeneratedBuildSources {

  /**
   * Where the user code starts in a generated source
   *
   * @param originalSource the build file the user code comes from
   * @param offset offset in the generated source of the first character of the user code
   * @param lineIdx zero-based index of the line of the generated source the user code starts on
   */
  final case class UserCodeStart(originalSource: os.Path, offset: Int, lineIdx: Int)

  def userCodeStart(generatedContent: String): Option[UserCodeStart] = {
    val marker = CodeGenConstants.userCodeStartMarker
    val markerIdx =
      if (generatedContent.startsWith(marker)) 0
      else generatedContent.indexOf("\n" + marker) match {
        case -1 => -1
        case n => n + 1
      }
    val markerLineEnd =
      if (markerIdx < 0) -1
      else generatedContent.indexOf('\n', markerIdx)
    if (markerLineEnd < 0) None
    else {
      val pathStr = generatedContent
        .substring(markerIdx + marker.length, markerLineEnd)
        .trim
      val pathOpt =
        try Some(os.Path(pathStr))
        catch { case _: IllegalArgumentException => None }
      pathOpt.map { path =>
        val offset = markerLineEnd + 1
        UserCodeStart(path, offset, generatedContent.iterator.take(offset).count(_ == '\n'))
      }
    }
  }

  /**
   * Splits a generated source into the code before and after the user code, given
   * the length of the build file it was generated from
   */
  def wrappers(
      generatedContent: String,
      originalLength: Int
  ): Option[(top: String, bottom: String)] =
    userCodeStart(generatedContent).flatMap { start =>
      val userCodeEnd = start.offset + originalLength
      Option.when(userCodeEnd <= generatedContent.length) {
        (
          top = generatedContent.take(start.offset),
          bottom = generatedContent.drop(userCodeEnd)
        )
      }
    }
}
