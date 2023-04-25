package mill.main

import mainargs.{MainData, TokenGrouping}
import mill.define._
import mill.util.EitherOps

import scala.collection.immutable

/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Reports an error if nothing is resolved.
 */
object Resolve {
  def resolveTasks(selector: List[Segment],
                   current: BaseModule,
                   discover: Discover[_],
                   args: Seq[String]): Either[String, Set[NamedTask[Any]]] = {
    Resolve.resolve(
      selector,
      Resolve.Resolved.Module(current),
      discover,
      args,
      Nil
    ) match {
      case Resolve.Success(value) =>
        val taskList: Set[Either[String, NamedTask[_]]] = value.collect {
          case Resolved.Target(value) => Right(value)
          case Resolved.Command(value) => value()
          case Resolved.Module(value: TaskModule) =>
            resolveDirectChildren(value, Some(value.defaultCommandName()), discover, args).values.head.flatMap{
              case Resolved.Target(value) => Right(value)
              case Resolved.Command(value) => value()
            }
        }

        if (taskList.nonEmpty) EitherOps.sequence(taskList).map(_.toSet[NamedTask[Any]])
        else Left(s"Cannot find default task to evaluate for module ${Segments(selector).render}")

      case Resolve.NotFound(segments, found, next, possibleNexts) =>
        val errorMsg = found.head match{
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

  sealed trait Resolved

  object Resolved {
    case class Module(value: mill.define.Module) extends Resolved
    case class Target(value: mill.define.Target[_]) extends Resolved
    case class Command(value: () => Either[String, mill.define.Command[_]])
        extends Resolved
  }

  sealed trait Result
  case class Success(value: Set[Resolved]) extends Result{
    assert(value.nonEmpty)
  }
  sealed trait Failed extends Result
  case class NotFound(deepest: Segments,
                      found: Set[Resolved],
                      next: Segment,
                      possibleNexts: Set[Segment]) extends Failed
  case class Error(msg: String) extends Failed



  def resolve(
    remainingSelector: List[Segment],
    current: Resolved,
    discover: Discover[_],
    args: Seq[String],
    revSelectorsSoFar0: List[Segment]
  ): Result = remainingSelector match {
    case Nil => Success(Set(current))
    case head :: tail =>
      val revSelectorsSoFar = head :: revSelectorsSoFar0
      def recurse(searchModules: Set[Resolved]): Result = {
        val (failures, successesLists) = searchModules
          .map(resolve(tail, _, discover, args, revSelectorsSoFar))
          .partitionMap{case s: Success => Right(s.value); case f: Failed => Left(f)}

        val (errors, notFounds) = failures.partitionMap{
          case s: NotFound => Right(s)
          case s: Error => Left(s.msg)
        }

        if (errors.nonEmpty) Error(errors.mkString("\n"))
        else if (successesLists.flatten.nonEmpty) Success(successesLists.flatten)
        else notFounds.size match{
          case 1 => notFounds.head
          case _ => notFoundResult(revSelectorsSoFar0, current, head, discover, args)
        }
      }

      (head, current) match {
        case (Segment.Label(singleLabel), Resolved.Module(obj)) =>
          EitherOps.sequence(
            singleLabel match {
              case "__" =>
                obj
                  .millInternal
                  .modules
                  .flatMap(m =>
                    Seq(Right(Resolved.Module(m))) ++
                      resolveDirectChildren(m, None, discover, args).values
                  )
              case "_" => resolveDirectChildren(obj, None, discover, args).values
              case _ => resolveDirectChildren(obj, Some(singleLabel), discover, args).values
            }
          ) match{
            case Left(err) => Error(err)
            case Right(v) => recurse(v.toSet)
          }

        case (Segment.Cross(cross), Resolved.Module(c: Cross[_])) =>
          val searchModules: Seq[Module] =
            if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
            else if (cross.contains("_")) {
              for {
                (segments, v) <- c.segmentsToModules.toList
                if segments.length == cross.length
                if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
              } yield v
            } else c.segmentsToModules.get(cross.toList).toSeq

          recurse(searchModules.map(m => Resolved.Module(m)).toSet)

        case _ => notFoundResult(revSelectorsSoFar0, current, head, discover, args)
      }
  }


  def resolveDirectChildren(
      obj: Module,
      nameOpt: Option[String] = None,
      discover: Discover[_],
      args: Seq[String]
  ): Map[Segment, Either[String, Resolved]] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modules = obj
      .millInternal
      .reflectNestedObjects[Module](namePred)
      .map(t => Segment.Label(t.millModuleSegments.parts.last) -> Right(Resolved.Module(t)))

    val crosses = obj match {
      case c: Cross[_] if nameOpt.isEmpty => c.segmentsToModules.map{case (k, v) => Segment.Cross(k) -> Right(Resolved.Module(v))}
      case _ => Nil
    }

    val targets = Module
      .reflect(obj.getClass, classOf[Target[_]], namePred)
      .map(m => Segment.Label(m.getName) -> Right(Resolved.Target(m.invoke(obj).asInstanceOf[Target[_]])))

    val commands = Module
      .reflect(obj.getClass, classOf[Command[_]], namePred)
      .map(_.getName)
      .map(name =>
        Segment.Label(name) -> Right(Resolved.Command(
          () =>
            invokeCommand(
              obj,
              name,
              discover.asInstanceOf[Discover[Module]],
              args
            ).head
        ))
      )

    (modules ++ crosses ++ targets ++ commands).toMap
  }

  def notFoundResult(revSelectorsSoFar0: List[Segment],
                     current: Resolved,
                     next: Segment,
                     discover: Discover[_],
                     args: Seq[String]) =
    NotFound(
      Segments(revSelectorsSoFar0.reverse),
      Set(current),
      next,
      current match {
        case Resolved.Module(obj) =>
          resolveDirectChildren(obj, None, discover, args).keySet

        case _ => Set()
      }
    )

  def invokeCommand(
      target: Module,
      name: String,
      discover: Discover[Module],
      rest: Seq[String]
  ): immutable.Iterable[Either[String, Command[_]]] = for {
    (cls, entryPoints) <- discover.value
    if cls.isAssignableFrom(target.getClass)
    ep <- entryPoints
    if ep._2.name == name
  } yield {
    mainargs.TokenGrouping.groupArgs(
      rest,
      ep._2.argSigs0,
      allowPositional = true,
      allowRepeats = false,
      allowLeftover = ep._2.leftoverArgSig.nonEmpty
    ).flatMap { grouped =>
      mainargs.Invoker.invoke(
        target,
        ep._2.asInstanceOf[MainData[_, Any]],
        grouped.asInstanceOf[TokenGrouping[Any]]
      )
    } match {
      case mainargs.Result.Success(v: Command[_]) => Right(v)
      case f: mainargs.Result.Failure =>
        Left(
          mainargs.Renderer.renderResult(
            ep._2,
            f,
            totalWidth = 100,
            printHelpOnError = true,
            docsOnNewLine = false,
            customName = None,
            customDoc = None
          )
        )
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
      .map { option => (option, LevenshteinDistance.editDistance(given, option))}
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
        (prefixSegments ++ Seq(Segment.Label(similar))).render+
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
