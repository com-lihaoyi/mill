package mill.resolve

import mill.define._
import mill.internal.EitherOps

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Returns only the [[Segments]] of the things it resolved, without reflecting
 * on the `java.lang.reflect.Member`s or instantiating the final tasks. Those
 * are left to downstream callers to do, with the except of instantiating
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
    case class Module(segments: Segments, cls: Class[?]) extends Resolved
    case class NamedTask(segments: Segments) extends Resolved
    case class Command(segments: Segments) extends Resolved
  }

  sealed trait Result

  case class Success(value: Seq[Resolved]) extends Result {
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

  /**
   * Cache for modules instantiated during task and resolution.
   *
   * Instantiating modules can be pretty expensive (~1ms per module!) due to all the reflection
   * and stuff going on, but because we only instantiate tasks and modules lazily on-demand, it
   * can be quite hard to figure out up front when we actually need to instantiate things. So
   * just cache all module instantiations and re-use them to avoid repeatedly instantiating the
   * same module
   */
  class Cache(
      val instantiatedModules: collection.mutable.Map[Segments, Either[String, Module]] =
        collection.mutable.Map(),
      decodedNames: collection.mutable.Map[String, String] = collection.mutable.Map(),
      methods: collection.mutable.Map[Class[?], Array[(java.lang.reflect.Method, String)]] =
        collection.mutable.Map()
  ) {
    def decode(s: String): String = {
      decodedNames.getOrElseUpdate(s, scala.reflect.NameTransformer.decode(s))
    }

    def getMethods(cls: Class[?]): Array[(Method, String)] = {
      methods.getOrElseUpdate(cls, Reflect.getMethods(cls, decode))
    }
  }

  def catchWrapException[T](t: => T): Either[String, T] = {
    try Right(t)
    catch {
      case e: InvocationTargetException =>
        makeResultException(e.getCause, new java.lang.Exception())
      case e: Exception => makeResultException(e, new java.lang.Exception())
    }
  }

  def makeResultException(e: Throwable, base: Exception): Left[String, Nothing] =
    mill.api.Result.makeResultException(e, base)

  def cyclicModuleErrorMsg(segments: Segments): String = {
    s"Cyclic module reference detected at ${segments.render}, " +
      s"it's required to wrap it in ModuleRef."
  }
  def resolve(
      rootModule: BaseModule,
      remainingQuery: List[Segment],
      current: Resolved,
      querySoFar: Segments,
      seenModules: Set[Class[?]],
      cache: Cache
  ): Result = {
    def moduleClasses(resolved: Iterable[Resolved]): Set[Class[?]] = {
      resolved.collect { case Resolved.Module(_, cls) => cls }.toSet
    }

    remainingQuery match {
      case Nil => Success(Seq(current))
      case head :: tail =>
        def recurse(searchModules: Seq[Resolved]): Result = {
          val (failures, successesLists) = searchModules
            .map { r =>
              val rClasses = moduleClasses(Set(r))
              if (seenModules.intersect(rClasses).nonEmpty) {
                Error(cyclicModuleErrorMsg(r.segments))
              } else {
                resolve(
                  rootModule,
                  tail,
                  r,
                  querySoFar ++ Seq(head),
                  seenModules ++ moduleClasses(Set(current)),
                  cache
                )
              }
            }
            .partitionMap { case s: Success => Right(s.value); case f: Failed => Left(f) }

          val (errors, notFounds) = failures.partitionMap {
            case s: NotFound => Right(s)
            case s: Error => Left(s.msg)
          }

          if (errors.nonEmpty) Error(errors.mkString("\n"))
          else if (successesLists.flatten.nonEmpty) Success(successesLists.flatten)
          else notFounds.size match {
            case 1 => notFounds.head
            case _ => notFoundResult(rootModule, querySoFar, current, head, cache)
          }

        }

        (head, current) match {
          case (Segment.Label(singleLabel), m: Resolved.Module) =>
            val resOrErr: Either[String, Seq[Resolved]] = singleLabel match {
              case "__" =>
                val self = Seq(Resolved.Module(m.segments, m.cls))
                val transitiveOrErr =
                  resolveTransitiveChildren(
                    rootModule,
                    m.cls,
                    None,
                    current.segments,
                    Nil,
                    seenModules,
                    cache
                  )

                transitiveOrErr.map(transitive => self ++ transitive)

              case "_" =>
                resolveDirectChildren(
                  rootModule,
                  m.cls,
                  None,
                  current.segments,
                  cache = cache
                )

              case pattern if pattern.startsWith("__:") =>
                val typePattern = pattern.split(":").drop(1)
                val self = Seq(Resolved.Module(m.segments, m.cls))

                val transitiveOrErr = resolveTransitiveChildren(
                  rootModule,
                  m.cls,
                  None,
                  current.segments,
                  typePattern,
                  seenModules,
                  cache
                )

                transitiveOrErr.map(transitive => self ++ transitive)

              case pattern if pattern.startsWith("_:") =>
                val typePattern = pattern.split(":").drop(1)
                resolveDirectChildren(
                  rootModule,
                  m.cls,
                  None,
                  current.segments,
                  typePattern,
                  cache
                )

              case _ =>
                resolveDirectChildren(
                  rootModule,
                  m.cls,
                  Some(singleLabel),
                  current.segments,
                  cache = cache
                )
            }

            resOrErr match {
              case Left(err) => Error(err)
              case Right(res) => recurse(res.distinct)
            }

          case (Segment.Cross(cross), m: Resolved.Module) =>
            if (classOf[Cross[?]].isAssignableFrom(m.cls)) {
              instantiateModule(rootModule, current.segments, cache).flatMap {
                case c: Cross[_] =>
                  catchWrapException(
                    if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
                    else if (cross.contains("_")) {
                      for {
                        (segments, v) <- c.segmentsToModules.toList
                        if segments.length == cross.length
                        if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
                      } yield v
                    } else {
                      val crossOrDefault = if (cross.isEmpty) {
                        // We want to select the default (first) crossValue
                        c.defaultCrossSegments
                      } else cross
                      c.segmentsToModules.get(crossOrDefault.toList).toSeq
                    }
                  )
              } match {
                case Left(err) => Error(err)
                case Right(searchModules) =>
                  recurse(
                    searchModules
                      .map(m => Resolved.Module(m.millModuleSegments, m.getClass))
                  )
              }

            } else notFoundResult(rootModule, querySoFar, current, head, cache)

          case _ => notFoundResult(rootModule, querySoFar, current, head, cache)
        }
    }
  }

  def instantiateModule(
      rootModule: BaseModule,
      segments: Segments,
      cache: Cache
  ): Either[String, Module] = cache.instantiatedModules.getOrElseUpdate(
    segments, {
      segments.value.foldLeft[Either[String, Module]](Right(rootModule)) {
        case (Right(current), Segment.Label(s)) =>
          assert(s != "_", s)
          resolveDirectChildren0(
            rootModule,
            current.millModuleSegments,
            current.getClass,
            Some(s),
            cache = cache
          ).flatMap {
            case Seq((_, Some(f))) => f(current)
            case unknown =>
              sys.error(
                s"Unable to resolve single child " +
                  s"rootModule: ${rootModule}, segments: ${segments.render}," +
                  s"current: $current, s: ${s}, unknown: $unknown"
              )
          }

        case (Right(current), Segment.Cross(vs)) =>
          assert(!vs.contains("_"), vs)

          catchWrapException(
            current
              .asInstanceOf[Cross[?]]
              .segmentsToModules(vs.toList)
              .asInstanceOf[Module]
          )

        case (Left(err), _) => Left(err)
      }

    }
  )

  def resolveTransitiveChildren(
      rootModule: BaseModule,
      cls: Class[?],
      nameOpt: Option[String],
      segments: Segments,
      typePattern: Seq[String],
      seenModules: Set[Class[?]],
      cache: Cache
  ): Either[String, Seq[Resolved]] = {
    if (seenModules.contains(cls)) Left(cyclicModuleErrorMsg(segments))
    else {
      val errOrDirect =
        resolveDirectChildren(rootModule, cls, nameOpt, segments, typePattern, cache)
      val directTraverse = resolveDirectChildren(rootModule, cls, nameOpt, segments, Nil, cache)

      val errOrModules = directTraverse.map { modules =>
        modules.flatMap {
          case m: Resolved.Module => Some(m)
          case _ => None
        }
      }

      val errOrIndirect0 = errOrModules match {
        case Right(modules) =>
          modules.flatMap { m =>
            Some(resolveTransitiveChildren(
              rootModule,
              m.cls,
              nameOpt,
              m.segments,
              typePattern,
              seenModules + cls,
              cache
            ))
          }
        case Left(err) => Seq(Left(err))
      }

      val errOrIndirect = EitherOps.sequence(errOrIndirect0).map(_.flatten)

      for {
        direct <- errOrDirect
        indirect <- errOrIndirect
      } yield direct ++ indirect
    }
  }

  private def resolveParents(c: Class[?]): Seq[Class[?]] =
    Seq(c) ++
      Option(c.getSuperclass).toSeq.flatMap(resolveParents) ++
      c.getInterfaces.flatMap(resolveParents)

  /**
   * Check if the given class matches a given type selector as string
   * @param cls
   * @param typePattern
   * @return
   */
  private def classMatchesTypePred(typePattern: Seq[String])(cls: Class[?]): Boolean =
    typePattern
      .forall { pat =>
        val negate = pat.startsWith("^") || pat.startsWith("!")
        val clsPat = pat.drop(if (negate) 1 else 0)

        // We split full class names by `.` and `$`
        // a class matches a type patter, if the type pattern segments match from the right
        // to express a full match, use `_root_` as first segment

        val typeNames = clsPat.split("[.$]").toSeq.reverse

        val parents = resolveParents(cls)
        val classNames = parents.flatMap(c =>
          ("_root_$" + c.getName).split("[.$]").toSeq.reverse.inits.toSeq.filter(_.nonEmpty)
        )

        val isOfType = classNames.contains(typeNames)
        if (negate) !isOfType else isOfType
      }

  def resolveDirectChildren(
      rootModule: BaseModule,
      cls: Class[?],
      nameOpt: Option[String],
      segments: Segments,
      typePattern: Seq[String] = Nil,
      cache: Cache
  ): Either[String, Seq[Resolved]] = {
    val crossesOrErr = if (classOf[Cross[?]].isAssignableFrom(cls) && nameOpt.isEmpty) {
      instantiateModule(rootModule, segments, cache).map {
        case cross: Cross[_] =>
          for (item <- cross.items) yield {
            Resolved.Module(segments ++ Segment.Cross(item.crossSegments), item.cls)
          }

        case _ => Nil
      }
    } else Right(Nil)

    def expandSegments(direct: Seq[(Resolved, Option[Module => Either[String, Module]])]) = {
      direct.map {
        case (Resolved.Module(s, cls), _) => Resolved.Module(segments ++ s, cls)
        case (Resolved.NamedTask(s), _) => Resolved.NamedTask(segments ++ s)
        case (Resolved.Command(s), _) => Resolved.Command(segments ++ s)
      }
    }

    for {
      crosses <- crossesOrErr
      filteredCrosses = crosses.filter { c =>
        classMatchesTypePred(typePattern)(c.cls)
      }
      direct0 <- resolveDirectChildren0(rootModule, segments, cls, nameOpt, typePattern, cache)
      direct <- Right(expandSegments(direct0))
    } yield direct ++ filteredCrosses
  }

  def resolveDirectChildren0(
      rootModule: BaseModule,
      segments: Segments,
      cls: Class[?],
      nameOpt: Option[String],
      typePattern: Seq[String] = Nil,
      cache: Cache
  ): Either[String, Seq[(Resolved, Option[Module => Either[String, Module]])]] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modulesOrErr: Either[String, Seq[(Resolved, Option[Module => Either[String, Module]])]] = {
      if (classOf[DynamicModule].isAssignableFrom(cls)) {
        instantiateModule(rootModule, segments, cache).map {
          case m: DynamicModule =>
            m.millModuleDirectChildren
              .filter(c => namePred(c.millModuleSegments.last.value))
              .filter(c => classMatchesTypePred(typePattern)(c.getClass))
              .map(c =>
                (
                  Resolved.Module(
                    Segments.labels(c.millModuleSegments.last.value),
                    c.getClass
                  ),
                  Some((x: Module) => Right(c))
                )
              )
        }
      } else Right {
        val reflectMemberObjects = Reflect
          .reflectNestedObjects02[Module](cls, namePred, cache.getMethods)
          .collect {
            case (name, memberCls, getter) if classMatchesTypePred(typePattern)(memberCls) =>
              val resolved = Resolved.Module(Segments.labels(cache.decode(name)), memberCls)
              val getter2 = Some((mod: Module) => catchWrapException(getter(mod)))
              (resolved, getter2)
          }

        reflectMemberObjects
      }
    }

    val namedTasks = Reflect
      .reflect(cls, classOf[NamedTask[?]], namePred, noParams = true, cache.getMethods)
      .map { m =>
        Resolved.NamedTask(Segments.labels(cache.decode(m.getName))) ->
          None
      }

    val commands = Reflect
      .reflect(cls, classOf[Command[?]], namePred, noParams = false, cache.getMethods)
      .map(m => cache.decode(m.getName))
      .map { name => Resolved.Command(Segments.labels(name)) -> None }

    modulesOrErr.map(_ ++ namedTasks ++ commands)
  }

  def notFoundResult(
      rootModule: BaseModule,
      querySoFar: Segments,
      current: Resolved,
      next: Segment,
      cache: Cache
  ): NotFound = {
    val possibleNexts = current match {
      case m: Resolved.Module =>
        resolveDirectChildren(
          rootModule,
          m.cls,
          None,
          current.segments,
          cache = cache
        ).toOption.get.map(
          _.segments.value.last
        )

      case _ => Set[Segment]()
    }

    NotFound(querySoFar, Set(current), next, possibleNexts.toSet)
  }
}
