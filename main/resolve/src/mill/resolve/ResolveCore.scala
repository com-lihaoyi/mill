package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.define._
import mill.util.EitherOps

import java.lang.reflect.InvocationTargetException
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
    case class Module(segments: Segments, valueOrErr: () => Either[String, mill.define.Module])
        extends Resolved

    case class Target(segments: Segments, valueOrErr: () => Either[String, mill.define.Target[_]])
        extends Resolved

    case class Command(segments: Segments, parent: mill.define.Module)
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

  def resolve(
      remainingQuery: List[Segment],
      current: Resolved,
      querySoFar: Segments
  ): Result = remainingQuery match {
    case Nil => Success(Set(current))
    case head :: tail =>
      def recurse(searchModules: Set[Resolved]): Result = {
        val (failures, successesLists) = searchModules
          .map(r => resolve(tail, r, querySoFar ++ Seq(head)))
          .partitionMap { case s: Success => Right(s.value); case f: Failed => Left(f) }

        val (errors, notFounds) = failures.partitionMap {
          case s: NotFound => Right(s)
          case s: Error => Left(s.msg)
        }

        if (errors.nonEmpty) Error(errors.mkString("\n"))
        else if (successesLists.flatten.nonEmpty) Success(successesLists.flatten)
        else notFounds.size match {
          case 1 => notFounds.head
          case _ => notFoundResult(querySoFar, current, head)
        }
      }

      (head, current) match {
        case (Segment.Label(singleLabel), m: Resolved.Module) =>
          val resOrErr = m.valueOrErr().flatMap { obj =>
            singleLabel match {
              case "__" =>
                val res = catchReflectException(obj.millInternal.modules)
                  .map(
                    _.flatMap(m =>
                      Seq(Resolved.Module(m.millModuleSegments, () => Right(m))) ++
                        resolveDirectChildren(m, None, m.millModuleSegments)
                    )
                  )

                res
              case "_" =>
                Right(resolveDirectChildren(obj, None, current.segments))
              case _ =>
                Right(resolveDirectChildren(obj, Some(singleLabel), current.segments))
            }
          }

          resOrErr match {
            case Left(err) => Error(err)
            case Right(res) => recurse(res.toSet)
          }

        case (Segment.Cross(cross), m: Resolved.Module) =>
          m.valueOrErr() match {
            case Right(c: Cross[_]) =>
              val searchModulesOrErr = catchNormalException(
                if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
                else if (cross.contains("_")) {
                  for {
                    (segments, v) <- c.segmentsToModules.toList
                    if segments.length == cross.length
                    if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
                  } yield v
                } else c.segmentsToModules.get(cross.toList).toSeq
              )

              searchModulesOrErr match {
                case Left(err) => Error(err)
                case Right(searchModules) =>
                  recurse(
                    searchModules
                      .map(m => Resolved.Module(m.millModuleSegments, () => Right(m)))
                      .toSet
                  )
              }

            case _ => notFoundResult(querySoFar, current, head)
          }

        case _ => notFoundResult(querySoFar, current, head)
      }
  }

  def catchReflectException[T](t: => T): Either[String, T] = {
    try Right(t)
    catch {
      case e: InvocationTargetException =>
        makeResultException(e.getCause, new Exception())
    }
  }

  def catchNormalException[T](t: => T): Either[String, T] = {
    try Right(t)
    catch { case e: Exception => makeResultException(e, new Exception()) }
  }

  def makeResultException(e: Throwable, base: Exception) = {
    val outerStack = new mill.api.Result.OuterStack(base.getStackTrace)
    Left(mill.api.Result.Exception(e, outerStack).toString)
  }

  def resolveDirectChildren(
      obj: Module,
      nameOpt: Option[String],
      segments: Segments
  ): Set[Resolved] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modules = obj
      .millInternal
      .reflectNestedObjects0[Module](namePred)
      .map { case (name, f) =>
        Resolved.Module(
          segments ++ Segment.Label(decode(name)),
          () => catchReflectException(f())
        )
      }

    val crosses = obj match {
      case c: Cross[_] if nameOpt.isEmpty =>
        c.segmentsToModules.map { case (k, v) =>
          Resolved.Module(
            segments ++ Segment.Cross(k),
            () => catchReflectException(v)
          )
        }
      case _ => Nil
    }

    val targets = Module
      .Internal
      .reflect(obj.getClass, classOf[Target[_]], namePred, noParams = true)
      .map { m =>
        Resolved.Target(
          segments ++ Segment.Label(decode(m.getName)),
          () => catchReflectException(m.invoke(obj).asInstanceOf[Target[_]])
        )
      }

    val commands = Module
      .Internal
      .reflect(obj.getClass, classOf[Command[_]], namePred, noParams = false)
      .map(m => decode(m.getName))
      .map { name =>
        Resolved.Command(segments ++ Segment.Label(name), obj)
      }

    (modules ++ crosses ++ targets ++ commands).toSet
  }

  def notFoundResult(querySoFar: Segments, current: Resolved, next: Segment) = {
    val possibleNextsOrErr = current match {
      case m: Resolved.Module =>
        m.valueOrErr().map(obj =>
          resolveDirectChildren(obj, None, querySoFar)
            .map(_.segments.value.last)
        )

      case _ => Right(Set[Segment]())
    }

    possibleNextsOrErr match {
      case Right(nexts) => NotFound(querySoFar, Set(current), next, nexts)
      case Left(err) => Error(err)
    }
  }
}
