package mill.main

import mill.define._
import mill.define.TaskModule
import mainargs.{MainData, TokenGrouping}
import mill.main.ResolveMetadata.singleModuleMeta

import scala.collection.immutable
import scala.reflect.ClassTag

object Resolve {

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
      direct: Seq[String],
      remaining: Seq[Segment],
      revSelectorsSoFar: Seq[Segment]
  ) = {
    errorMsgBase(
      direct,
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

  def invokeCommand(
      target: Module,
      name: String,
      discover: Discover[Module],
      rest: Seq[String]
  ): immutable.Iterable[Either[String, Command[_]]] =
    for {
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

  def runDefault(
      obj: Module,
      last: Segment,
      discover: Discover[_],
      rest: Seq[String]
  ): Array[Option[Either[String, Command[_]]]] = {
    for {
      child <- obj.millInternal.reflectNestedObjects[Module](_ == last.pathSegments.last)
      res <- child match {
        case taskMod: TaskModule =>
          Some(
            invokeCommand(
              child,
              taskMod.defaultCommandName(),
              discover.asInstanceOf[Discover[Module]],
              rest
            ).headOption
          )
        case _ => None
      }
    } yield res
  }.toArray
}

abstract class Resolve[R: ClassTag] {
  def endResolveCross(
      obj: Module,
      last: List[String],
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, Seq[R]]
  def endResolveLabel(
      obj: Module,
      last: String,
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, Seq[R]]

  def resolve(
      remainingSelector: List[Segment],
      obj: mill.Module,
      discover: Discover[_],
      rest: Seq[String],
      remainingCrossSelectors: List[List[String]]
  ): Either[String, Seq[R]] = {

    remainingSelector match {
      case Segment.Cross(last) :: Nil =>
        endResolveCross(obj, last.map(_.toString).toList, discover, rest)
      case Segment.Label(last) :: Nil =>
        endResolveLabel(obj, last, discover, rest)

      case head :: tail =>
        def recurse(
            searchModules: Seq[Module],
            resolveFailureMsg: => Left[String, Nothing]
        ): Either[String, Seq[R]] = {
          val matching = searchModules
            .map(m => resolve(tail, m, discover, rest, remainingCrossSelectors))

          matching match {
            case Seq(Left(err)) => Left(err)
            case items =>
              items.collect { case Right(v) => v } match {
                case Nil => resolveFailureMsg
                case values => Right(values.flatten)
              }
          }
        }
        head match {
          case Segment.Label(singleLabel) =>
            recurse(
              singleLabel match {
                case "__" => obj.millInternal.modules
                case "_" => obj.millModuleDirectChildren
                case _ =>
                  obj
                    .millInternal.reflectNestedObjects[Module](_ == singleLabel)
                    .headOption
                    .toSeq
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
                    singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
                    remainingSelector,
                    obj.millModuleSegments.value
                  )
                case _ =>
                  Resolve.errorMsgLabel(
                    singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
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
          case Segment.Cross(cross) =>
            obj match {
              case c: Cross[_] =>
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
                  searchModules = searchModules,
                  resolveFailureMsg =
                    Resolve.errorMsgCross(
                      c.segmentsToModules.map(_._1.map(_.toString)).toList,
                      cross.map(_.toString),
                      obj.millModuleSegments.value
                    )
                )
              case _ =>
                Left(
                  Resolve.unableToResolve(Segment.Cross(cross.map(_.toString)), tail) +
                    Resolve.hintListLabel(tail)
                )
            }
        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
