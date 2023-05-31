package mill.resolve

import mill.api.internal
import mill.define.{BaseModule, Discover, Segment, Segments}
import mill.resolve.ResolveCore.Resolved

/**
 * Reports errors in the case where nothing was resolved
 */
private object ResolveNotFoundHandler {
  def apply(
      selector: Segments,
      segments: Segments,
      found: Set[Resolved],
      next: Segment,
      possibleNexts: Set[Segment]
  ): String = {

    if (found.head.isInstanceOf[Resolved.Module]) {
      next match {
        case Segment.Label(s) =>
          val possibleStrings = possibleNexts.collect { case Segment.Label(s) => s }
          errorMsgLabel(s, possibleStrings, segments, selector)

        case Segment.Cross(keys) =>
          val possibleCrossKeys = possibleNexts.collect { case Segment.Cross(keys) => keys }
          errorMsgCross(keys, possibleCrossKeys, segments, selector)
      }
    } else {
      unableToResolve((segments ++ Seq(next)).render) +
        s" ${segments.render} resolves to a Task with no children."
    }
  }

  def unableToResolve(segments: String): String = "Cannot resolve " + segments + "."

  def hintList(revSelectorsSoFar: Segments) = {
    val search = revSelectorsSoFar.render
    s" Try `mill resolve $search` to see what's available."
  }

  def hintListLabel(revSelectorsSoFar: Segments) = {
    hintList(revSelectorsSoFar ++ Segment.Label("_"))
  }

  def findMostSimilar(`given`: String, options: Set[String]): Option[String] = {
    options
      .map { option => (option, LevenshteinDistance.editDistance(`given`, option)) }
      .filter(_._2 < 3)
      .minByOption(_._2)
      .map(_._1)
  }

  def errorMsgLabel(
      given: String,
      possibleMembers: Set[String],
      prefixSegments: Segments,
      fullSegments: Segments
  ) = {
    val suggestion = findMostSimilar(given, possibleMembers) match {
      case None => hintListLabel(prefixSegments)
      case Some(similar) =>
        " Did you mean " +
          (prefixSegments ++ Segment.Label(similar)).render +
          "?"
    }

    val msg = unableToResolve(fullSegments.render) + suggestion

    msg
  }

  def errorMsgCross(
      givenKeys: Seq[String],
      possibleCrossKeys: Set[Seq[String]],
      prefixSegments: Segments,
      fullSegments: Segments
  ) = {

    val suggestion = findMostSimilar(
      givenKeys.mkString(","),
      possibleCrossKeys.map(_.mkString(","))
    ) match {
      case None => hintListLabel(prefixSegments)
      case Some(similar) =>
        " Did you mean " +
          (prefixSegments ++ Segment.Cross(similar.split(','))).render +
          "?"
    }

    unableToResolve(fullSegments.render) + suggestion
  }
}
