package mill.main

import mainargs.{MainData, TokenGrouping}
import mill.define._
import mill.util.EitherOps
import scala.reflect.NameTransformer.decode

import scala.collection.immutable

/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Returns a [[Result]], either containing a [[Success]] containing the
 * [[Resolved]] set, [[NotFound]] if it couldn't find anything with some
 * metadata about what it was looking for, or [[Error]] if something blew up.
 */
object ResolveCore {

  sealed trait Resolved {
    def segments: Segments
  }

  object Resolved {
    case class Module(segments: Segments, valueOrErr: Either[String, mill.define.Module])
        extends Resolved

    case class Target(segments: Segments, valueOrErr: Either[String, mill.define.Target[_]])
        extends Resolved

    case class Command(segments: Segments, valueOrErr: Either[String, mill.define.Command[_]])
        extends Resolved
  }

  sealed trait Result
  case class Success(value: Set[Resolved]) extends Result {
    assert(value.nonEmpty)
  }
  sealed trait Failed extends Result
  case class NotFound(
      deepest: Segments,
      found: Set[Resolved],
      next: Segment,
      possibleNexts: Set[Segment]
  ) extends Failed
  case class Error(msg: String) extends Failed

  def resolve(remainingQuery: List[Segment],
              current: Resolved,
              discover: Discover[_],
              args: Seq[String],
              querySoFar: Segments
  ): Result = remainingQuery match {
    case Nil => Success(Set(current))
    case head :: tail =>
      def recurse(searchModules: Set[Resolved]): Result = {
        val (failures, successesLists) = searchModules
          .map(r => resolve(tail, r, discover, args, querySoFar ++ Seq(head)))
          .partitionMap { case s: Success => Right(s.value); case f: Failed => Left(f) }

        val (errors, notFounds) = failures.partitionMap {
          case s: NotFound => Right(s)
          case s: Error => Left(s.msg)
        }

        if (errors.nonEmpty) Error(errors.mkString("\n"))
        else if (successesLists.flatten.nonEmpty) Success(successesLists.flatten)
        else notFounds.size match {
          case 1 => notFounds.head
          case _ => notFoundResult(querySoFar, current, head, discover, args)
        }
      }

      (head, current) match {
        case (Segment.Label(singleLabel), m: Resolved.Module) =>
          val resOrErr = m.valueOrErr.flatMap { obj =>
            singleLabel match {
              case "__" =>
                val res = catchReflectException(
                  obj
                    .millInternal
                    .modules
                ).map(
                  _.flatMap(m =>
                    Seq(Resolved.Module(m.millModuleSegments, Right(m))) ++
                      resolveDirectChildren(m, None, discover, args, m.millModuleSegments)
                  )
                )

                res
              case "_" =>
                Right(resolveDirectChildren(obj, None, discover, args, current.segments))
              case _ =>
                Right(resolveDirectChildren(obj, Some(singleLabel), discover, args, current.segments))
            }
          }

          resOrErr match {
            case Left(err) => Error(err)
            case Right(res) => recurse(res.toSet)
          }

        case (Segment.Cross(cross), Resolved.Module(_, Right(c: Cross[_]))) =>
          val searchModules: Seq[Module] =
            if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
            else if (cross.contains("_")) {
              for {
                (segments, v) <- c.segmentsToModules.toList
                if segments.length == cross.length
                if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
              } yield v
            } else c.segmentsToModules.get(cross.toList).toSeq

          recurse(searchModules.map(m => Resolved.Module(m.millModuleSegments, Right(m))).toSet)

        case _ => notFoundResult(querySoFar, current, head, discover, args)
      }
  }

  def catchReflectException[T](t: => T): Either[String, T] = {
    try Right(t)
    catch {
      case e: java.lang.reflect.InvocationTargetException =>
        val outerStack = new mill.api.Result.OuterStack(new Exception().getStackTrace)
        Left(mill.api.Result.Exception(e.getCause, outerStack).toString)
    }
  }

  def resolveDirectChildren(
      obj: Module,
      nameOpt: Option[String] = None,
      discover: Discover[_],
      args: Seq[String],
      segments: Segments
  ): Set[Resolved] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modules = obj
      .millInternal
      .reflectNestedObjects0[Module](namePred)
      .map { case (name, f) =>
        Resolved.Module(
          segments ++ Segment.Label(decode(name)),
          catchReflectException(f())
        )
      }

    val crosses = obj match {
      case c: Cross[_] if nameOpt.isEmpty =>
        c.segmentsToModules.map { case (k, v) =>
          Resolved.Module(
            segments ++ Segment.Cross(k),
            catchReflectException(v)
          )
        }
      case _ => Nil
    }

    val targets = Module
      .reflect(obj.getClass, classOf[Target[_]], namePred, noParams = true)
      .map { m =>
        Resolved.Target(
          segments ++ Segment.Label(decode(m.getName)),
          catchReflectException(m.invoke(obj).asInstanceOf[Target[_]])
        )
      }

    val commands = Module
      .reflect(obj.getClass, classOf[Command[_]], namePred, noParams = false)
      .map(m => decode(m.getName))
      .map { name =>
        Resolved.Command(
          segments ++ Segment.Label(name),
          catchReflectException(
            invokeCommand(
              obj,
              name,
              discover.asInstanceOf[Discover[Module]],
              args
            ).head
          ).flatten
        )
      }

    (modules ++ crosses ++ targets ++ commands).toSet
  }

  def notFoundResult(
      querySoFar: Segments,
      current: Resolved,
      next: Segment,
      discover: Discover[_],
      args: Seq[String]
  ) = {
    val possibleNextsOrErr = current match {
      case m: Resolved.Module =>
        m.valueOrErr.map(obj =>
          resolveDirectChildren(obj, None, discover, args, querySoFar)
            .map(_.segments.value.last)
        )

      case _ => Right(Set[Segment]())
    }

    possibleNextsOrErr match {
      case Right(nexts) => NotFound(querySoFar, Set(current), next, nexts)
      case Left(err) => Error(err)
    }
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

      case mainargs.Result.Failure.Exception(e) =>
        val outerStack = new mill.api.Result.OuterStack(new Exception().getStackTrace)
        Left(mill.api.Result.Exception(e, outerStack).toString)

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
}
