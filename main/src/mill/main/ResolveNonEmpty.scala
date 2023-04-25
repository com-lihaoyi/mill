package mill.main

import mill.define.{BaseModule, Discover, Segment, Segments}
import mill.main.ResolveCore.Resolved

/**
 * Wraps [[ResolveCore]] to report error messages if nothing was resolved
 */
object ResolveNonEmpty{
  def resolveNonEmpty(
                       selector: List[Segment],
                       current: BaseModule,
                       discover: Discover[_],
                       args: Seq[String]
                     ): Either[String, Set[Resolved]] = {
    ResolveCore.resolve(
      selector,
      ResolveCore.Resolved.Module(current),
      discover,
      args,
      Nil
    ) match {
      case ResolveCore.Success(value) => Right(value)

      case ResolveCore.NotFound(segments, found, next, possibleNexts) =>
        val errorMsg = found.head match {
          case s: Resolved.Module =>
            next match {
              case Segment.Label(s) =>
                val possibleStrings = possibleNexts.collect { case Segment.Label(s) => s }

                errorMsgLabel(s, possibleStrings, segments, Segments(selector))
              case Segment.Cross(keys) =>
                val possibleCrossKeys = possibleNexts.collect { case Segment.Cross(keys) => keys }
                errorMsgCross(keys, possibleCrossKeys, segments, Segments(selector))
            }
          case x =>
            unableToResolve((segments ++ Seq(next)).render) +
              s" ${segments.render} resolves to a Task with no children."
        }

        Left(errorMsg)

      case ResolveCore.Error(value) => Left(value)
    }
  }


  def unableToResolve(segments: String): String = "Cannot resolve " + segments + "."

  def hintList(revSelectorsSoFar: Seq[Segment]) = {
    val search = Segments(revSelectorsSoFar).render
    s" Try `mill resolve $search` to see what's available."
  }

  def hintListLabel(revSelectorsSoFar: Seq[Segment]) = {
    hintList(revSelectorsSoFar :+ Segment.Label("_"))
  }

  def findMostSimilar(given: String, options: Set[String]): Option[String] = {
    options
      .map { option => (option, LevenshteinDistance.editDistance(given, option)) }
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
      case None => hintListLabel(prefixSegments.value)
      case Some(similar) =>
        " Did you mean " +
          (prefixSegments ++ Seq(Segment.Label(similar))).render +
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
      case None => hintListLabel(prefixSegments.value)
      case Some(similar) =>
        " Did you mean " +
          (prefixSegments ++ Seq(Segment.Cross(similar.split(',')))).render +
          "?"
    }

    unableToResolve(fullSegments.render) + suggestion
  }
}
