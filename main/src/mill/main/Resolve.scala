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
  def resolveTasks(remainingSelector: List[Segment],
                   current: BaseModule,
                   discover: Discover[_],
                   args: Seq[String]): Either[String, Set[NamedTask[Any]]] = {
    Resolve.resolve(
      remainingSelector,
      Resolve.Resolved.Module("", current),
      discover,
      args,
      Nil
    ) match {
      case Resolve.Success(value) =>
        val taskList: Set[Either[String, NamedTask[_]]] = value.collect {
          case Resolved.Target(name, value) => Right(value)
          case Resolved.Command(name, value) => value()
          case Resolved.Module(name, value: TaskModule) =>
            resolveDirectChildren(value, Some(value.defaultCommandName()), discover, args).head.flatMap{
              case Resolved.Target(name, value) => Right(value)
              case Resolved.Command(name, value) => value()
            }
        }

        if (taskList.nonEmpty) EitherOps.sequence(taskList).map(_.toSet[NamedTask[Any]])
        else Left(s"Cannot find default task to evaluate for module ${Segments(remainingSelector).render}")

      case Resolve.NotFound(segments, found, next, possibleNexts) =>
        val errorMsg = found.head match{
          case s: Resolved.Module =>
            next match {
              case Segment.Label(s) =>
                val possibleStrings = possibleNexts.collect { case Segment.Label(s) => s }

                errorMsgLabel(s, possibleStrings, segments)
              case Segment.Cross(keys) =>
                val possibleCrossKeys = possibleNexts.collect { case Segment.Cross(keys) => keys }
                errorMsgCross(keys, possibleCrossKeys, segments)
            }
          case _ =>

            unableToResolve((segments ++ Seq(next)).render) +
            " Task " + segments.render + " is not a module and has no children."
        }

        Left(errorMsg)

      case Resolve.Error(value) => Left(value)
    }
  }

  sealed trait Resolved{
    def name: String
  }

  object Resolved {
    case class Module(name: String, value: mill.define.Module) extends Resolved
    case class Target(name: String, value: mill.define.Target[_]) extends Resolved
    case class Command(name: String, value: () => Either[String, mill.define.Command[_]])
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
    case Nil =>
      Success(Set(current))
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
        else {
          val successes = successesLists.flatten
          if (successes.nonEmpty) Success(successes)
          else {
            notFounds.headOption.getOrElse {
              notFoundResult(revSelectorsSoFar0, current, head, discover, args)
            }
          }
        }
      }

      (head, current) match {
        case (Segment.Label(singleLabel), Resolved.Module(_, obj)) =>
          lazy val directChildren = resolveDirectChildren(obj, None, discover, args)
          EitherOps.sequence(
            singleLabel match {
              case "__" =>
                obj
                  .millInternal
                  .modules
                  .flatMap(m =>
                    Seq(Right(Resolved.Module(m.millModuleSegments.parts.lastOption.getOrElse(""), m))) ++
                      resolveDirectChildren(m, None, discover, args)
                  )
              case "_" => directChildren
              case _ => resolveDirectChildren(obj, Some(singleLabel), discover, args)
            }
          ) match{
            case Left(err) => Error(err)
            case Right(v) => recurse(v.toSet)
          }

        case (Segment.Cross(cross), Resolved.Module(_, c: Cross[_])) =>
          val searchModules: Seq[Module] =
            if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
            else if (cross.contains("_")) {
              for {
                (segments, v) <- c.segmentsToModules.toList
                if segments.length == cross.length
                if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
              } yield v
            } else c.segmentsToModules.get(cross.toList).toSeq

          recurse(searchModules.map(m => Resolved.Module("", m)).toSet)

        case _ => notFoundResult(revSelectorsSoFar0, current, head, discover, args)
      }
  }


  def resolveDirectChildren(
      obj: Module,
      nameOpt: Option[String] = None,
      discover: Discover[_],
      args: Seq[String]
  ): Set[Either[String, Resolved]] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modules = obj
      .millInternal
      .reflectNestedObjects[Module](namePred)
      .map(t => Right(Resolved.Module(t.millModuleSegments.parts.last, t)))

    val targets = Module
      .reflect(obj.getClass, classOf[Target[_]], namePred)
      .map(m => Right(Resolved.Target(m.getName, m.invoke(obj).asInstanceOf[Target[_]])))

    val commands = Module
      .reflect(obj.getClass, classOf[Command[_]], namePred)
      .map(_.getName)
      .map(name =>
        Right(Resolved.Command(
          name,
          () =>
            invokeCommand(
              obj,
              name,
              discover.asInstanceOf[Discover[Module]],
              args
            ).head
        ))
      )

    (modules ++ targets ++ commands).toSet
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
        case Resolved.Module(_, c: Cross[_]) =>
          c.segmentsToModules.keys.toSet.map(Segment.Cross)

        case Resolved.Module(_, obj) =>
          resolveDirectChildren(obj, None, discover, args).map(e => Segment.Label(e.right.get.name))

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

  def hintListCross(revSelectorsSoFar: Seq[Segment]) = {
    hintList(revSelectorsSoFar :+ Segment.Cross(Seq("__")))
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
                    segments: Segments) = {
    def render(x: String) = (segments ++ Seq(Segment.Label(x))).render

    val suggestion = findMostSimilar(given, possibleMembers) match {
      case None => hintListLabel(segments.value)
      case Some(similar) => " Did you mean " + render(similar) + "?"
    }

    val msg = unableToResolve(render(given)) + suggestion

    msg
  }

  def errorMsgCross(givenKeys: Seq[String],
                    possibleCrossKeys: Set[Seq[String]],
                    segments: Segments) = {

    def render(xs: Seq[String]) = (segments ++ Seq(Segment.Cross(xs))).render


    val suggestion = findMostSimilar(
      givenKeys.mkString(","),
      possibleCrossKeys.map(_.mkString(","))
    ) match {
      case None => hintListLabel(segments.value)
      case Some(similar) => " Did you mean " + render(similar.split(',')) + "?"
    }

    unableToResolve(render(givenKeys)) + suggestion
  }

}
