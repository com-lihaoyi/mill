package mill.resolve

import mill.define._
import mill.util.EitherOps

import java.lang.reflect.InvocationTargetException
import scala.collection.immutable.Seq
import scala.reflect.NameTransformer.decode

/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Returns only the [[Segments]] of the things it resolved, without reflecting
 * on the `java.lang.reflect.Member`s or instantiating the final tasks. Those
 * are left to downstream callers to do, with the exception of instantiating
 * [[mill.define.Cross]] modules which is needed to identify their cross
 * values which is necessary for resolving tasks within them.
 *
 * Returns a [[Result]], either containing a [[Success]] containing the
 * [[Resolved]] set, [[NotFound]] if it couldn't find anything with some
 * metadata about what it was looking for, or [[Error]] if something blew up.
 */
private object ResolveCore {
  sealed trait Resolved {
    def segments: Segments
  }

  object Resolved {
    case class Module(segments: Segments, cls: Class[_]) extends Resolved
    case class Target(segments: Segments) extends Resolved
    case class Command(segments: Segments) extends Resolved
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

  def catchWrapException[T](t: => T): Either[String, T] = {
    try Right(t)
    catch {
      case e: InvocationTargetException => makeResultException(e.getCause, new Exception())
      case e: Exception => makeResultException(e, new Exception())
    }
  }

  def makeResultException(e: Throwable, base: Exception) = {
    val outerStack = new mill.api.Result.OuterStack(base.getStackTrace)
    Left(mill.api.Result.Exception(e, outerStack).toString)
  }

  def resolve(
      rootModule: Module,
      remainingQuery: List[Segment],
      current: Resolved,
      querySoFar: Segments
  ): Result = {
    remainingQuery match {
      case Nil => Success(Set(current))
      case head :: tail =>
        def recurse(searchModules: Set[Resolved]): Result = {
          val (failures, successesLists) = searchModules
            .map(r => resolve(rootModule, tail, r, querySoFar ++ Seq(head)))
            .partitionMap { case s: Success => Right(s.value); case f: Failed => Left(f) }

          val (errors, notFounds) = failures.partitionMap {
            case s: NotFound => Right(s)
            case s: Error => Left(s.msg)
          }

          if (errors.nonEmpty) Error(errors.mkString("\n"))
          else if (successesLists.flatten.nonEmpty) Success(successesLists.flatten)
          else notFounds.size match {
            case 1 => notFounds.head
            case _ => notFoundResult(rootModule, querySoFar, current, head)
          }
        }

        (head, current) match {
          case (Segment.Label(singleLabel), m: Resolved.Module) =>
            val resOrErr = singleLabel match {
              case "__" =>
                val self = Seq(Resolved.Module(m.segments, m.cls))
                val transitiveOrErr =
                  resolveTransitiveChildren(rootModule, m.cls, None, current.segments)

                transitiveOrErr.map(transitive => self ++ transitive)

              case "_" =>
                resolveDirectChildren(rootModule, m.cls, None, current.segments)

              case _ =>
                resolveDirectChildren(rootModule, m.cls, Some(singleLabel), current.segments)
            }

            resOrErr match {
              case Left(err) => Error(err)
              case Right(res) => recurse(res.toSet)
            }

          case (Segment.Cross(cross), m: Resolved.Module) =>
            if (classOf[Cross[_]].isAssignableFrom(m.cls)) {
              instantiateModule(rootModule, current.segments).flatMap { case c: Cross[_] =>
                catchWrapException(
                  if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
                  else if (cross.contains("_")) {
                    for {
                      (segments, v) <- c.segmentsToModules.toList
                      if segments.length == cross.length
                      if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
                    } yield v
                  } else c.segmentsToModules.get(cross.toList).toSeq
                )
              } match {
                case Left(err) => Error(err)
                case Right(searchModules) =>
                  recurse(
                    searchModules
                      .map(m => Resolved.Module(m.millModuleSegments, m.getClass))
                      .toSet
                  )
              }

            } else notFoundResult(rootModule, querySoFar, current, head)

          case _ => notFoundResult(rootModule, querySoFar, current, head)
        }
    }
  }

  def instantiateModule(rootModule: Module, segments: Segments): Either[String, Module] = {
    segments.value.foldLeft[Either[String, Module]](Right(rootModule)) {
      case (Right(current), Segment.Label(s)) =>
        assert(s != "_", s)
        resolveDirectChildren0(
          rootModule,
          current.millModuleSegments,
          current.getClass,
          Some(s)
        ).flatMap {
          case Seq((_, Some(f))) => f(current)
          case unknown =>
            sys.error(
              s"Unable to resolve single child " +
                s"rootModule: $rootModule, segments: ${segments.render}," +
                s"current: $current, s: ${s}, unknown: $unknown"
            )
        }

      case (Right(current), Segment.Cross(vs)) =>
        assert(!vs.contains("_"), vs)

        catchWrapException(
          current
            .asInstanceOf[Cross[_]]
            .segmentsToModules(vs.toList)
            .asInstanceOf[Module]
        )

      case (Left(err), _) => Left(err)
    }
  }

  def resolveTransitiveChildren(
      rootModule: Module,
      cls: Class[_],
      nameOpt: Option[String],
      segments: Segments
  ): Either[String, Set[Resolved]] = {
    for {
      direct <- resolveDirectChildren(rootModule, cls, nameOpt, segments)
      indirect0 = direct
        .collect { case m: Resolved.Module =>
          resolveTransitiveChildren(rootModule, m.cls, nameOpt, m.segments)
        }
      indirect <- EitherOps.sequence(indirect0).map(_.flatten)
    } yield direct ++ indirect
  }

  def resolveDirectChildren(
      rootModule: Module,
      cls: Class[_],
      nameOpt: Option[String],
      segments: Segments
  ): Either[String, Set[Resolved]] = {

    val crossesOrErr = if (classOf[Cross[_]].isAssignableFrom(cls) && nameOpt.isEmpty) {
      instantiateModule(rootModule: Module, segments).map {
        case cross: Cross[_] =>
          for (item <- cross.items) yield {
            Resolved.Module(segments ++ Segment.Cross(item.crossSegments), item.cls)
          }

        case _ => Nil
      }
    } else Right(Nil)

    crossesOrErr.flatMap { crosses =>
      resolveDirectChildren0(rootModule, segments, cls, nameOpt)
        .map(
          _.map {
            case (Resolved.Module(s, cls), _) => Resolved.Module(segments ++ s, cls)
            case (Resolved.Target(s), _) => Resolved.Target(segments ++ s)
            case (Resolved.Command(s), _) => Resolved.Command(segments ++ s)
          }
            .toSet
            .++(crosses)
        )
    }
  }

  def resolveDirectChildren0(
      rootModule: Module,
      segments: Segments,
      cls: Class[_],
      nameOpt: Option[String]
  ): Either[String, Seq[(Resolved, Option[Module => Either[String, Module]])]] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modulesOrErr: Either[String, Seq[(Resolved, Option[Module => Either[String, Module]])]] =
      if (classOf[DynamicModule].isAssignableFrom(cls)) {
        instantiateModule(rootModule, segments).map {
          case m: DynamicModule =>
            m.millModuleDirectChildren
              .filter(c => namePred(c.millModuleSegments.parts.last))
              .map(c =>
                (
                  Resolved.Module(
                    Segments.labels(c.millModuleSegments.parts.last),
                    c.getClass
                  ),
                  Some((x: Module) => Right(c))
                )
              )
        }
      } else Right {
        Reflect
          .reflectNestedObjects0[Module](cls, namePred)
          .map { case (name, member) =>
            Resolved.Module(
              Segments.labels(decode(name)),
              member match {
                case f: java.lang.reflect.Field => f.getType
                case f: java.lang.reflect.Method => f.getReturnType
              }
            ) -> (
              member match {
                case f: java.lang.reflect.Field =>
                  Some((x: Module) => catchWrapException(f.get(x).asInstanceOf[Module]))

                case f: java.lang.reflect.Method =>
                  Some((x: Module) => catchWrapException(f.invoke(x).asInstanceOf[Module]))
              }
            )
          }
      }

    val targets = Reflect
      .reflect(cls, classOf[Target[_]], namePred, noParams = true)
      .map { m =>
        Resolved.Target(Segments.labels(decode(m.getName))) ->
          None
      }

    val commands = Reflect
      .reflect(cls, classOf[Command[_]], namePred, noParams = false)
      .map(m => decode(m.getName))
      .map { name => Resolved.Command(Segments.labels(name)) -> None }

    modulesOrErr.map(_ ++ targets ++ commands)
  }

  def notFoundResult(rootModule: Module, querySoFar: Segments, current: Resolved, next: Segment) = {
    val possibleNexts = current match {
      case m: Resolved.Module =>
        resolveDirectChildren(rootModule, m.cls, None, current.segments).right.get.map(
          _.segments.value.last
        )

      case _ => Set[Segment]()
    }

    NotFound(querySoFar, Set(current), next, possibleNexts)
  }
}
