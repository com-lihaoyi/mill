package mill.main

import mainargs.{MainData, TokenGrouping}
import mill.define._
import mill.util.EitherOps

import scala.collection.immutable

object ResolveTasks{
  def resolve(
    remainingSelector: List[Segment],
    current: BaseModule,
    discover: Discover[_],
    args: Seq[String]
  ): Either[String, Seq[NamedTask[Any]]] = {
    val raw = Resolve.resolve(
      remainingSelector,
      Resolve.Resolved.Module("", current),
      discover,
      args,
      Nil
    )
      .map(_.collect {
        case t: Resolve.Resolved.Target => Right(t.value)
        case t: Resolve.Resolved.Command => t.value()
      })

    val cooked = raw.map(EitherOps.sequence(_))

    cooked.flatten
  }
}
/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Reports an error if nothing is resolved.
 */
object Resolve {
  sealed trait Resolved{
    def name: String
  }
  object Resolved {
    case class Module(name: String, value: mill.define.Module) extends Resolved
    case class Target(name: String, value: mill.define.Target[_]) extends Resolved
    case class Command(name: String, value: () => Either[String, mill.define.Command[_]])
        extends Resolved
  }

  def resolve(
    remainingSelector: List[Segment],
    current: Resolved,
    discover: Discover[_],
    args: Seq[String],
    revSelectorsSoFar: List[Segment]
  ): Either[String, Seq[Resolved]] = remainingSelector match {
    case Nil => Right(Seq(current))

    case head :: tail =>
      def recurse(searchModules: Seq[Either[String, Resolved]],
                  resolveFailureMsg: => Left[String, Nothing]): Either[String, Seq[Resolved]] = {
        val (errors, successes) = searchModules
          .map(_.flatMap(resolve(tail, _, discover, args, head :: revSelectorsSoFar)))
          .partitionMap(identity)

        if (errors.nonEmpty) Left(errors.mkString("\n"))
        else successes.flatten match{
          case Nil => resolveFailureMsg
          case values => Right(values)
        }
      }

      (head, current) match {
        case (Segment.Label(singleLabel), Resolved.Module(_, obj)) =>
          lazy val directChildren = resolveDirectChildren(obj, None, discover, args)
          recurse(
            singleLabel match {
              case "__" =>
                obj
                  .millInternal
                  .modules
                  .flatMap(m =>
                    Seq(Right(Resolved.Module(m.millModuleSegments.parts.last, m))) ++
                      resolveDirectChildren(m, None, discover, args)
                  )
              case "_" => directChildren
              case _ => resolveDirectChildren(obj, Some(singleLabel), discover, args)
            },
            singleLabel match {
              case "_" =>
                Left(
                  "Cannot resolve " + Segments(
                    (remainingSelector.reverse ++ obj.millModuleSegments.value).reverse: _*
                  ).render +
                    ". Try `mill resolve " + Segments(
                    (Segment.Label("_") +: obj.millModuleSegments.value).reverse: _*
                  ).render + "` to see what's available."
                )
              case "__" =>
                Resolve.errorMsgLabel(
                  directChildren.map(_.right.get.name),
                  remainingSelector,
                  obj.millModuleSegments.value
                )
              case _ =>
                Resolve.errorMsgLabel(
                  directChildren.map(_.right.get.name),
                  Seq(Segment.Label(singleLabel)),
                  obj.millModuleSegments.value
                )
            }
          ).map(
            _.distinctBy {
              case t: NamedTask[_] => t.ctx.segments
              case t => t
            }
          )

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

          recurse(
            searchModules.map(m => Right(Resolved.Module("", m))),
            Resolve.errorMsgCross(
              c.segmentsToModules.map(_._1.map(_.toString)).toList,
              cross.map(_.toString),
              c.millModuleSegments.value
            )
          )

        case _ => Right(Nil)
      }
  }

  def resolveDirectChildren(
      obj: Module,
      nameOpt: Option[String] = None,
      discover: Discover[_],
      args: Seq[String]
  ): Seq[Either[String, Resolved]] = {

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

    modules ++ targets ++ commands
  }

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

  def unableToResolve(last: Segment, revSelectorsSoFar: Seq[Segment]): String = {
    unableToResolve(Segments((last +: revSelectorsSoFar).reverse: _*).render)
  }

  def unableToResolve(segments: String): String = "Cannot resolve " + segments + "."


  def hintList(revSelectorsSoFar: Seq[Segment]) = {
    val search = Segments(revSelectorsSoFar: _*).render
    s" Try `mill resolve $search` to see what's available."
  }

  def hintListLabel(revSelectorsSoFar: Seq[Segment]) = {
    hintList(revSelectorsSoFar :+ Segment.Label("_"))
  }

  def hintListCross(revSelectorsSoFar: Seq[Segment]) = {
    hintList(revSelectorsSoFar :+ Segment.Cross(Seq("__")))
  }

  def errorMsgBase[T](
                       direct: Seq[T],
                       last0: T,
                       editSplit: String => String,
                       defaultErrorMsg: String
                     )(strings: T => Seq[String], render: T => String): Left[String, Nothing] = {
    val last = strings(last0)
    val similar =
      direct
        .map(x => (x, strings(x)))
        .filter(_._2.length == last.length)
        .map { case (d, s) =>
          (
            d,
            s.zip(last).map { case (a, b) => LevenshteinDistance.editDistance(editSplit(a), b) }.sum
          )
        }
        .filter(_._2 < 3)
        .sortBy(_._2)

    if (similar.headOption.exists(_._1 == last0)) {
      // Special case: if the most similar segment is the desired segment itself,
      // this means we are trying to resolve a module where a task is present.
      // Special case the error message to make it something meaningful
      Left("Task " + last0 + " is not a module and has no children.")
    } else {

      val hint = similar match {
        case Nil => defaultErrorMsg
        case items => " Did you mean " + render(items.head._1) + "?"
      }
      Left(unableToResolve(render(last0)) + hint)
    }
  }

  def errorMsgLabel(
                     possibleMembers: Seq[String],
                     remaining: Seq[Segment],
                     revSelectorsSoFar: Seq[Segment]
                   ) = {
    errorMsgBase(
      possibleMembers,
      Segments(revSelectorsSoFar ++ remaining: _*).render,
      _.split('.').last,
      hintListLabel(revSelectorsSoFar)
    )(
      rendered => Seq(rendered.split('.').last),
      x => x
    )
  }

  def errorMsgCross(
                     crossKeys: Seq[Seq[String]],
                     last: Seq[String],
                     revSelectorsSoFar: Seq[Segment]
                   ) = {
    errorMsgBase(
      crossKeys,
      last,
      x => x,
      hintListCross(revSelectorsSoFar)
    )(
      crossKeys => crossKeys,
      crossKeys => Segments((Segment.Cross(crossKeys) +: revSelectorsSoFar).reverse: _*).render
    )
  }

}
