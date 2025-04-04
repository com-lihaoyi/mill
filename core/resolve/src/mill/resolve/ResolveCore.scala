package mill.resolve

import mill.define.*
import mill.define.internal.Reflect

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
      val instantiatedModules: collection.mutable.Map[Segments, mill.api.Result[Module]] =
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

  def catchWrapException[T](t: => T): mill.api.Result[T] = {
    try mill.api.Result.Success(t)
    catch {
      case e: InvocationTargetException =>
        mill.api.Result.Failure(makeResultException(e.getCause, new java.lang.Exception()).left.get)
      case e: Exception =>
        mill.api.Result.Failure(makeResultException(e, new java.lang.Exception()).left.get)
    }
  }

  def makeResultException(e: Throwable, base: Exception): Left[String, Nothing] =
    mill.api.ExecResult.makeResultException(e, base)

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
                  // `foo.__` wildcards can refer to `foo` as well, so make sure we don't
                  // mark it as seen to avoid spurious cyclic module reference errors
                  seenModules ++ moduleClasses(Option.when(r != current)(current)),
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
            val resOrErr: mill.api.Result[Seq[Resolved]] = singleLabel match {
              case "__" =>
                val self = Seq(Resolved.Module(m.segments, m.cls))
                val transitiveOrErr =
                  resolveTransitiveChildren(
                    rootModule,
                    m.cls,
                    None,
                    current.segments,
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
                  seenModules,
                  cache
                )

                transitiveOrErr.map(transitive =>
                  (self ++ transitive).collect {
                    case r @ Resolved.Module(segments, cls)
                        if classMatchesTypePred(typePattern)(cls) =>
                      r
                  }
                )

              case pattern if pattern.startsWith("_:") =>
                val typePattern = pattern.split(":").drop(1)
                resolveDirectChildren(
                  rootModule,
                  m.cls,
                  None,
                  current.segments,
                  cache
                ).map {
                  _.collect {
                    case r @ Resolved.Module(segments, cls)
                        if classMatchesTypePred(typePattern)(cls) => r
                  }
                }

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
              case mill.api.Result.Failure(err) => Error(err)
              case mill.api.Result.Success(res) => recurse(res.distinct)
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
                case mill.api.Result.Failure(err) => Error(err)
                case mill.api.Result.Success(searchModules) =>
                  recurse(
                    searchModules
                      .map(m => Resolved.Module(m.moduleSegments, m.getClass))
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
  ): mill.api.Result[Module] = cache.instantiatedModules.getOrElseUpdate(
    segments, {
      segments.value.foldLeft[mill.api.Result[Module]](mill.api.Result.Success(rootModule)) {
        case (mill.api.Result.Success(current), Segment.Label(s)) =>
          assert(s != "_", s)
          resolveDirectChildren0(
            rootModule,
            current.moduleSegments,
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

        case (mill.api.Result.Success(current), Segment.Cross(vs)) =>
          assert(!vs.contains("_"), vs)

          catchWrapException(
            current
              .asInstanceOf[Cross[?]]
              .segmentsToModules(vs.toList)
              .asInstanceOf[Module]
          )

        case (mill.api.Result.Failure(err), _) => mill.api.Result.Failure(err)
      }

    }
  )

  def resolveTransitiveChildren(
      rootModule: BaseModule,
      cls: Class[?],
      nameOpt: Option[String],
      segments: Segments,
      seenModules: Set[Class[?]],
      cache: Cache
  ): mill.api.Result[Seq[Resolved]] = {
    if (seenModules.contains(cls)) mill.api.Result.Failure(cyclicModuleErrorMsg(segments))
    else {
      val errOrDirect = resolveDirectChildren(rootModule, cls, nameOpt, segments, cache)
      val directTraverse = resolveDirectChildren(rootModule, cls, nameOpt, segments, cache)

      val errOrModules = directTraverse.map { modules =>
        modules.flatMap {
          case m: Resolved.Module => Some(m)
          case _ => None
        }
      }

      val errOrIndirect0 = errOrModules match {
        case mill.api.Result.Success(modules) =>
          modules.flatMap { m =>
            Some(resolveTransitiveChildren(
              rootModule,
              m.cls,
              nameOpt,
              m.segments,
              seenModules + cls,
              cache
            ))
          }
        case mill.api.Result.Failure(err) => Seq(mill.api.Result.Failure(err))
      }

      val errOrIndirect = mill.api.Result.sequence(errOrIndirect0).map(_.flatten)

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
      cache: Cache
  ): mill.api.Result[Seq[Resolved]] = {
    val crossesOrErr = if (classOf[Cross[?]].isAssignableFrom(cls) && nameOpt.isEmpty) {
      instantiateModule(rootModule, segments, cache).map {
        case cross: Cross[_] =>
          for (item <- cross.items) yield {
            Resolved.Module(segments ++ Segment.Cross(item.crossSegments), item.cls)
          }

        case _ => Nil
      }
    } else mill.api.Result.Success(Nil)

    def expandSegments(direct: Seq[(Resolved, Option[Module => mill.api.Result[Module]])]) = {
      direct.map {
        case (Resolved.Module(s, cls), _) => Resolved.Module(segments ++ s, cls)
        case (Resolved.NamedTask(s), _) => Resolved.NamedTask(segments ++ s)
        case (Resolved.Command(s), _) => Resolved.Command(segments ++ s)
      }
    }

    for {
      crosses <- crossesOrErr
      direct0 <- resolveDirectChildren0(rootModule, segments, cls, nameOpt, cache)
      direct <- mill.api.Result.Success(expandSegments(direct0))
    } yield direct ++ crosses
  }

  def resolveDirectChildren0(
      rootModule: BaseModule,
      segments: Segments,
      cls: Class[?],
      nameOpt: Option[String],
      cache: Cache
  ): mill.api.Result[Seq[(Resolved, Option[Module => mill.api.Result[Module]])]] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modulesOrErr
        : mill.api.Result[Seq[(Resolved, Option[Module => mill.api.Result[Module]])]] = {
      if (classOf[DynamicModule].isAssignableFrom(cls)) {
        instantiateModule(rootModule, segments, cache).map {
          case m: DynamicModule =>
            m.moduleDirectChildren
              .filter(c => namePred(c.moduleSegments.last.value))
              .map(c =>
                (
                  Resolved.Module(
                    Segments.labels(c.moduleSegments.last.value),
                    c.getClass
                  ),
                  Some((x: Module) => mill.api.Result.Success(c))
                )
              )
        }
      } else mill.api.Result.Success {
        val reflectMemberObjects = Reflect
          .reflectNestedObjects02[Module](cls, namePred, cache.getMethods)
          .collect {
            case (name, memberCls, getter) =>
              val resolved = Resolved.Module(Segments.labels(cache.decode(name)), memberCls)
              val getter2 = Some((mod: Module) => catchWrapException(getter(mod)))
              (resolved, getter2)
          }
          .toSeq

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
