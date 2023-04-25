package mill.main

import mill.define.{BaseModule, Discover, NamedTask, Segment, Segments, TaskModule}
import mill.main.Resolve.Resolved
import mill.util.EitherOps

import scala.util.Either


object ResolveSegments extends Resolver[Segments] {
  def resolve(selector: List[Segment],
              current: BaseModule,
              discover: Discover[_],
              args: Seq[String]) = {
    resolveNonEmpty(selector, current, discover, args).map { value => value.map(_.segments) }
  }
}

object ResolveMetadata extends Resolver[String] {
  def resolve(selector: List[Segment],
              current: BaseModule,
              discover: Discover[_],
              args: Seq[String]) = {
    resolveNonEmpty(selector, current, discover, args).map { value => value.map(_.segments.render) }
  }
}

object ResolveTasks extends Resolver[NamedTask[Any]]{
  def resolve(selector: List[Segment],
              current: BaseModule,
              discover: Discover[_],
              args: Seq[String]) = {
    resolveNonEmpty(selector, current, discover, args).flatMap{ value =>
      val taskList: Set[Either[String, NamedTask[_]]] = value.collect {
        case Resolved.Target(value) => Right(value)
        case Resolved.Command(value) => value()
        case Resolved.Module(value: TaskModule) =>
          Resolve.resolveDirectChildren(value, Some(value.defaultCommandName()), discover, args).values.head.flatMap {
            case Resolved.Target(value) => Right(value)
            case Resolved.Command(value) => value()
          }
      }

      if (taskList.nonEmpty) EitherOps.sequence(taskList).map(_.toSet[NamedTask[Any]])
      else Left(s"Cannot find default task to evaluate for module ${Segments(selector).render}")
    }

  }
}


trait Resolver[T]{
  def resolve(selector: List[Segment],
              current: BaseModule,
              discover: Discover[_],
              args: Seq[String]): Either[String, Set[T]]

  def resolveNonEmpty(selector: List[Segment],
                      current: BaseModule,
                      discover: Discover[_],
                      args: Seq[String]): Either[String, Set[Resolved]] = {
    Resolve.resolve(
      selector,
      Resolve.Resolved.Module(current),
      discover,
      args,
      Nil
    ) match {
      case Resolve.Success(value) => Right(value)

      case Resolve.NotFound(segments, found, next, possibleNexts) =>
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

      case Resolve.Error(value) => Left(value)
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

  def findMostSimilar(given: String,
                      options: Set[String]): Option[String] = {
    options
      .map { option => (option, LevenshteinDistance.editDistance(given, option)) }
      .filter(_._2 < 3)
      .minByOption(_._2)
      .map(_._1)
  }

  def errorMsgLabel(given: String,
                    possibleMembers: Set[String],
                    prefixSegments: Segments,
                    fullSegments: Segments) = {
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

  def errorMsgCross(givenKeys: Seq[String],
                    possibleCrossKeys: Set[Seq[String]],
                    prefixSegments: Segments,
                    fullSegments: Segments) = {

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
