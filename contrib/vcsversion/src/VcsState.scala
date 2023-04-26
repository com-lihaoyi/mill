package mill.vcsversion

import scala.util.Try

case class VcsState(
    currentRevision: String,
    lastTag: Option[String],
    commitsSinceLastTag: Int,
    dirtyHash: Option[String],
    vcs: Option[Vcs]
) {

  def format(
      noTagFallback: String = "0.0.0",
      countSep: String = "-",
      commitCountPad: Byte = 0,
      revSep: String = "-",
      revHashDigits: Int = 6,
      dirtySep: String = "-DIRTY",
      dirtyHashDigits: Int = 8,
      tagModifier: String => String = stripV,
      untaggedSuffix: String = ""
  ): String = {
    val versionPart = tagModifier(lastTag.getOrElse(noTagFallback))

    val isUntagged = lastTag.isEmpty || commitsSinceLastTag > 0

    val commitCountPart = if (isUntagged) {
      s"$countSep${if (commitCountPad > 0) {
          (10000000000000L + commitsSinceLastTag).toString().substring(14 - commitCountPad, 14)
        } else if (commitCountPad == 0) commitsSinceLastTag
        else ""}"
    } else ""

    val revisionPart = if (isUntagged) {
      s"$revSep${currentRevision.take(revHashDigits)}"
    } else ""

    val dirtyPart = dirtyHash match {
      case None => ""
      case Some(d) => dirtySep + d.take(dirtyHashDigits)
    }

    val snapshotSuffix = if (isUntagged) untaggedSuffix else ""

    s"$versionPart$commitCountPart$revisionPart$dirtyPart$snapshotSuffix"
  }

  /**
   * By default we strip the leading v if a user uses it.
   * Ex. v2.3.2 -> 2.3.2
   * @param tag the tag to process
   * @return either the stripped tag or the tag verbatim
   */
  def stripV(tag: String): String =
    tag match {
      case t if t.startsWith("v") && Try(t.substring(1, 2).toInt).isSuccess =>
        t.substring(1)
      case t => t
    }

  @deprecated(
    "Binary compatibility shim. Use other overload instead.",
    "mill-vcs-version after 0.2.0"
  )
  private[vcsversion] def format(
      noTagFallback: String,
      countSep: String,
      commitCountPad: Byte,
      revSep: String,
      revHashDigits: Int,
      dirtySep: String,
      dirtyHashDigits: Int,
      tagModifier: String => String
  ): String = format(
    noTagFallback = noTagFallback,
    countSep = countSep,
    commitCountPad = commitCountPad,
    revSep = revSep,
    revHashDigits = revHashDigits,
    dirtySep = dirtySep,
    dirtyHashDigits = dirtyHashDigits,
    tagModifier = tagModifier,
    untaggedSuffix = ""
  )
}

object VcsState {
  implicit val jsonify: upickle.default.ReadWriter[VcsState] = upickle.default.macroRW
}
