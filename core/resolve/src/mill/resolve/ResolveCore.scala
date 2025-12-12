package mill.resolve

import mill.api.*
import mill.api.internal.{Reflect, Resolved, RootModule0}

import java.lang.reflect.Method

/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Returns only the [[Segments]] of the things it resolved, without reflecting
 * on the `java.lang.reflect.Member`s or instantiating the final tasks. Those
 * are left to downstream callers to do, with the except of instantiating
 * [[mill.api.Cross]] modules which is needed to identify their cross
 * values which is necessary for resolving tasks within them.
 *
 * Returns a [[Result]], either containing a [[Success]] containing the
 * [[Resolved]] set, [[NotFound]] if it couldn't find anything with some
 * metadata about what it was looking for, or [[Error]] if something blew up.
 */
private object ResolveCore {

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

  case class Error(failure: Result.Failure) extends Failed

  /**
   * Convert a label to potential cross segment values by replacing underscores with
   * `.`, `/`, or `:` since those characters have special meaning in Mill selectors.
   * Returns the original label and variants with substitutions applied.
   */
  def labelToCrossSegments(label: String): Seq[String] = {
    if (!label.contains('_')) Seq(label)
    else {
      // Generate variants by replacing underscores with `.`, `/`, or `:`
      // We prioritize `.` as it's the most common (e.g., version numbers)
      val withDots = label.replace('_', '.')
      val withSlashes = label.replace('_', '/')
      val withColons = label.replace('_', ':')
      Seq(label, withDots, withSlashes, withColons).distinct
    }
  }

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
      val instantiatedModules: collection.mutable.Map[
        (RootModule0, Segments),
        mill.api.Result[Module]
      ] =
        collection.mutable.Map(),
      decodedNames: collection.mutable.Map[String, String] = collection.mutable.Map(),
      methods: collection.mutable.Map[(Class[?], Boolean, Class[?]), Array[(
          java.lang.reflect.Method,
          String
      )]] =
        collection.mutable.Map()
  ) {
    def decode(s: String): String = {
      decodedNames.getOrElseUpdate(s, scala.reflect.NameTransformer.decode(s))
    }

    def getMethods(cls: Class[?], noParams: Boolean, inner: Class[?]): Array[(Method, String)] = {
      methods.getOrElseUpdate(
        (cls, noParams, inner),
        Reflect.getMethods(cls, noParams, inner, decode)
      )

    }
  }

  def cyclicModuleErrorMsg(segments: Segments): String = {
    s"Cyclic module reference detected at ${segments.render}, " +
      s"it's required to wrap it in ModuleRef."
  }
  def resolve(
      rootModule: RootModule0,
      rootModulePrefix: String,
      remainingQuery: List[Segment],
      current: Resolved,
      querySoFar: Segments,
      seenModules: Set[Class[?]],
      cache: Cache
  ): Result = {

    def moduleClasses(resolved: Iterable[Resolved]): Set[Class[?]] = {
      resolved.collect { case Resolved.Module(_, _, _, cls) => cls }.toSet
    }

    remainingQuery match {
      case Nil => Success(Seq(current))
      case head :: tail =>
        def recurse(searchModules: Seq[Resolved]): Result = {
          val (failures, successesLists) = searchModules
            .map { r =>
              val rClasses = moduleClasses(Set(r))
              if (seenModules.intersect(rClasses).nonEmpty) {
                Error(Result.Failure(cyclicModuleErrorMsg(r.taskSegments)))
              } else {
                resolve(
                  rootModule,
                  rootModulePrefix,
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
            .partitionMap {
              case s: Success => Right(s.value)
              case f: Failed => Left(f)
            }

          val (resFailures, notFounds) = failures.partitionMap {
            case s: NotFound => Right(s)
            case s: Error => Left(s.failure)
          }

          if (resFailures.nonEmpty) Error(Result.Failure.join(resFailures))
          else if (successesLists.flatten.nonEmpty) Success(successesLists.flatten)
          else notFounds.size match {
            case 1 => notFounds.head
            case _ =>
              notFoundResult(rootModule, rootModulePrefix, querySoFar, current, head, cache)
          }

        }

        (head, current) match {
          case (Segment.Label(singleLabel), m: Resolved.Module) =>
            val resOrErr: mill.api.Result[Seq[Resolved]] = singleLabel match {
              case "__" =>
                val self =
                  Seq(Resolved.Module(rootModule, m.rootModulePrefix, m.taskSegments, m.cls))
                val transitiveOrErr =
                  resolveTransitiveChildren(
                    rootModule,
                    rootModulePrefix,
                    m.cls,
                    None,
                    current.taskSegments,
                    seenModules,
                    cache
                  )

                transitiveOrErr.map(transitive => self ++ transitive)

              case "_" =>
                resolveDirectChildren(
                  rootModule,
                  rootModulePrefix,
                  m.cls,
                  None,
                  current.taskSegments,
                  cache = cache
                )

              case pattern if pattern.startsWith("__:") =>
                val typePattern = pattern.split(":").drop(1)
                val self =
                  Seq(Resolved.Module(rootModule, m.rootModulePrefix, m.taskSegments, m.cls))

                val transitiveOrErr = resolveTransitiveChildren(
                  rootModule,
                  rootModulePrefix,
                  m.cls,
                  None,
                  current.taskSegments,
                  seenModules,
                  cache
                )

                transitiveOrErr.map(transitive =>
                  (self ++ transitive).collect {
                    case r @ Resolved.Module(_, _, _, cls)
                        if classMatchesTypePred(typePattern)(cls) =>
                      r
                  }
                )

              case pattern if pattern.startsWith("_:") =>
                val typePattern = pattern.split(":").drop(1)
                resolveDirectChildren(
                  rootModule,
                  rootModulePrefix,
                  m.cls,
                  None,
                  current.taskSegments,
                  cache
                ).map {
                  _.collect {
                    case r @ Resolved.Module(_, _, _, cls)
                        if classMatchesTypePred(typePattern)(cls) => r
                  }
                }

              case _ =>
                resolveDirectChildren(
                  rootModule,
                  rootModulePrefix,
                  m.cls,
                  Some(singleLabel),
                  current.taskSegments,
                  cache = cache
                )
            }

            resOrErr match {
              case f: mill.api.Result.Failure => Error(f)
              case mill.api.Result.Success(res) if res.nonEmpty => recurse(res.distinct)
              case mill.api.Result.Success(_) =>
                // Direct resolution found nothing; try label-as-cross if this is a Cross module
                tryResolveLabelAsCross(
                  rootModule,
                  rootModulePrefix,
                  m,
                  singleLabel,
                  tail,
                  querySoFar,
                  seenModules,
                  cache
                ).getOrElse(
                  notFoundResult(rootModule, rootModulePrefix, querySoFar, current, head, cache)
                )
            }

          case (Segment.Cross(cross), m: Resolved.Module) =>
            if (classOf[Cross[?]].isAssignableFrom(m.cls)) {
              instantiateModule(
                rootModule,
                rootModulePrefix,
                current.taskSegments,
                cache
              ).flatMap {
                case c: Cross[_] =>
                  mill.api.ExecResult.catchWrapException(
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
                case f: mill.api.Result.Failure => Error(f)
                case mill.api.Result.Success(searchModules) =>
                  recurse(
                    searchModules
                      .map(m =>
                        Resolved.Module(
                          rootModule,
                          rootModulePrefix,
                          m.moduleSegments,
                          m.getClass
                        )
                      )
                  )
              }

            } else notFoundResult(rootModule, rootModulePrefix, querySoFar, current, head, cache)

          case _ => notFoundResult(rootModule, rootModulePrefix, querySoFar, current, head, cache)
        }
    }
  }

  def instantiateModule(
      rootModule: RootModule0,
      rootModulePrefix: String,
      segments: Segments,
      cache: Cache
  ): mill.api.Result[Module] = cache.instantiatedModules.getOrElseUpdate(
    (rootModule, segments), {
      segments.value.foldLeft[mill.api.Result[Module]](mill.api.Result.Success(rootModule)) {
        case (mill.api.Result.Success(current), Segment.Label(s)) =>
          assert(s != "_", s)
          resolveDirectChildren0(
            rootModule,
            rootModulePrefix,
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

          mill.api.ExecResult.catchWrapException(
            current
              .asInstanceOf[Cross[?]]
              .segmentsToModules(vs.toList)
              .asInstanceOf[Module]
          )

        case (f: mill.api.Result.Failure, _) => f
      }

    }
  )

  def resolveTransitiveChildren(
      rootModule: RootModule0,
      rootModulePrefix: String,
      cls: Class[?],
      nameOpt: Option[String],
      taskSegments: Segments,
      seenModules: Set[Class[?]],
      cache: Cache
  ): mill.api.Result[Seq[Resolved]] = {
    if (seenModules.contains(cls)) mill.api.Result.Failure(cyclicModuleErrorMsg(taskSegments))
    else {
      val errOrDirect =
        resolveDirectChildren(rootModule, rootModulePrefix, cls, nameOpt, taskSegments, cache)
      val directTraverse =
        resolveDirectChildren(rootModule, rootModulePrefix, cls, nameOpt, taskSegments, cache)

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
              rootModulePrefix,
              m.cls,
              nameOpt,
              m.taskSegments,
              seenModules + cls,
              cache
            ))
          }
        case f: mill.api.Result.Failure => Seq(f)
      }

      val errOrIndirect = mill.api.Result.sequence(errOrIndirect0).map(_.flatten)

      for ((direct, indirect) <- errOrDirect.zip(errOrIndirect))
        yield direct ++ indirect
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
      rootModule: RootModule0,
      rootModulePrefix: String,
      cls: Class[?],
      nameOpt: Option[String],
      segments: Segments,
      cache: Cache
  ): mill.api.Result[Seq[Resolved]] = {
    val crossesOrErr = if (classOf[Cross[?]].isAssignableFrom(cls) && nameOpt.isEmpty) {
      instantiateModule(rootModule, rootModulePrefix, segments, cache).map {
        case cross: Cross[_] =>
          for (item <- cross.items) yield {
            Resolved.Module(
              rootModule,
              rootModulePrefix,
              segments ++ Segment.Cross(item.crossSegments),
              item.cls
            )
          }

        case _ => Nil
      }
    } else mill.api.Result.Success(Nil)

    def expandSegments(direct: Seq[(Resolved, Option[Module => mill.api.Result[Module]])]) = {
      direct.map {
        case (Resolved.Module(rootModule, rootModulePrefix, s, cls), _) =>
          Resolved.Module(rootModule, rootModulePrefix, segments ++ s, cls)
        case (Resolved.NamedTask(rootModule, rootModulePrefix, s, enclosing), _) =>
          Resolved.NamedTask(rootModule, rootModulePrefix, segments ++ s, enclosing)
        case (Resolved.Command(rootModule, rootModulePrefix, s, enclosing), _) =>
          Resolved.Command(rootModule, rootModulePrefix, segments ++ s, enclosing)
      }
    }

    for {
      (crosses, direct0) <- crossesOrErr
        .zip(resolveDirectChildren0(rootModule, rootModulePrefix, segments, cls, nameOpt, cache))
    } yield expandSegments(direct0) ++ crosses
  }

  def resolveDirectChildren0(
      rootModule: RootModule0,
      rootModulePrefix: String,
      segments: Segments,
      cls: Class[?],
      nameOpt: Option[String],
      cache: Cache
  ): mill.api.Result[Seq[(Resolved, Option[Module => mill.api.Result[Module]])]] = {
    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modulesOrErr
        : mill.api.Result[Seq[(Resolved, Option[Module => mill.api.Result[Module]])]] = {
      if (classOf[DynamicModule].isAssignableFrom(cls)) {
        instantiateModule(rootModule, rootModulePrefix, segments, cache).map {
          case m: DynamicModule =>
            m.moduleDirectChildren
              .filter(c => namePred(c.moduleSegments.last.value))
              .map(c =>
                (
                  Resolved.Module(
                    rootModule,
                    rootModulePrefix,
                    Segments.labels(c.moduleSegments.last.value),
                    c.getClass
                  ),
                  Some((_: Module) => mill.api.Result.Success(c))
                )
              )
        }
      } else mill.api.Result.Success {
        Reflect
          .reflectNestedObjects02[Module](cls, namePred, cache.getMethods)
          .collect {
            case (name, memberCls, getter) =>
              val resolved =
                Resolved.Module(
                  rootModule,
                  rootModulePrefix,
                  Segments.labels(cache.decode(name)),
                  memberCls
                )
              val getter2 =
                Some((mod: Module) => mill.api.ExecResult.catchWrapException(getter(mod)))
              (resolved, getter2)
          }
          .toSeq
      }
    }

    val namedTasks = Reflect
      .reflect(cls, classOf[Task.Named[?]], namePred, noParams = true, cache.getMethods)
      .map { m =>
        Resolved.NamedTask(
          rootModule,
          rootModulePrefix,
          Segments.labels(cache.decode(m.getName)),
          cls
        ) ->
          None
      }

    val commands = Reflect
      .reflect(cls, classOf[Task.Command[?]], namePred, noParams = false, cache.getMethods)
      .map(m => cache.decode(m.getName))
      .map { name =>
        Resolved.Command(rootModule, rootModulePrefix, Segments.labels(name), cls) -> None
      }

    modulesOrErr.map(_ ++ namedTasks ++ commands)
  }

  /**
   * Try to resolve a label segment as a cross module value.
   *
   * For example, `foo.bar.qux` can resolve to `foo.bar[qux]` if `bar` is a Cross.Module,
   * or `foo.bar.qux.baz` can resolve to `foo.bar[qux,baz]` if `bar` is a Cross.Module2.
   *
   * Also handles underscore-to-dot substitution: `foo.bar.qux_baz` can resolve to `foo.bar[qux.baz]`.
   *
   * Returns Some(Result) if a match was found, None otherwise.
   */
  def tryResolveLabelAsCross(
      rootModule: RootModule0,
      rootModulePrefix: String,
      m: Resolved.Module,
      firstLabel: String,
      tail: List[Segment],
      querySoFar: Segments,
      seenModules: Set[Class[?]],
      cache: Cache
  ): Option[Result] = {
    if (!classOf[Cross[?]].isAssignableFrom(m.cls)) None
    else instantiateModule(rootModule, rootModulePrefix, m.taskSegments, cache) match {
      case f: mill.api.Result.Failure => Some(Error(f))
      case mill.api.Result.Success(c: Cross[_]) =>
        c.items.headOption.flatMap { item =>
          val dimensions = item.crossSegments.length

          // Collect label segments: firstLabel + up to (dimensions - 1) more from tail
          val additionalLabels = tail.take(dimensions - 1).collect { case Segment.Label(l) => l }

          val allLabels = firstLabel :: additionalLabels

          // Try different combinations with underscore-to-dot conversion and find matching cross modules
          val labelCombinations = generateCrossSegmentCombinations(allLabels)

          val matchedModules = labelCombinations
            .flatMap { segments => c.segmentsToModules.get(segments).toSeq }
            .distinct

          if (matchedModules.isEmpty) None
          else { // We found matches! Now recurse with the remaining tail
            val crossSegment = Segment.Cross(
              c.segmentsToModules.keys
                .find(segs => matchedModules.contains(c.segmentsToModules(segs)))
                .getOrElse(allLabels)
            )

            val resolvedModules = matchedModules.map { crossMod =>
              Resolved.Module(
                rootModule,
                rootModulePrefix,
                crossMod.moduleSegments,
                crossMod.getClass
              )
            }

            val actualRemainingTail = tail.drop(dimensions - 1)

            // Recurse with remaining query
            val results = resolvedModules.map { resolved =>
              resolve(
                rootModule,
                rootModulePrefix,
                actualRemainingTail,
                resolved,
                querySoFar ++ Seq(crossSegment),
                seenModules ++ Set(m.cls),
                cache
              )
            }

            // Combine results
            val (failures, successes) = results.partitionMap {
              case s: Success => Right(s.value)
              case f: Failed => Left(f)
            }

            val (errors, notFounds) = failures.partitionMap {
              case e: Error => Left(e.failure)
              case n: NotFound => Right(n)
            }

            if (errors.nonEmpty) Some(Error(mill.api.Result.Failure.join(errors)))
            else if (successes.flatten.nonEmpty) Some(Success(successes.flatten))
            else if (notFounds.nonEmpty) Some(notFounds.head)
            else None
          }
        }
    }
  }

  /**
   * Generate all combinations of cross segments by replacing underscores with dots.
   * For example, ["2_12_20", "jvm"] generates combinations like:
   * - ["2_12_20", "jvm"]
   * - ["2.12.20", "jvm"]
   */
  def generateCrossSegmentCombinations(labels: List[String]): Seq[List[String]] = {
    labels match {
      case Nil => Seq(Nil)
      case head :: tail =>
        val headVariants = labelToCrossSegments(head)
        val tailCombinations = generateCrossSegmentCombinations(tail)
        for(h <- headVariants; t <- tailCombinations) yield h :: t
    }
  }

  def notFoundResult(
      rootModule: RootModule0,
      rootModulePrefix: String,
      querySoFar: Segments,
      current: Resolved,
      next: Segment,
      cache: Cache
  ): NotFound = {
    val possibleNexts = current match {
      case m: Resolved.Module =>
        resolveDirectChildren(
          rootModule,
          rootModulePrefix,
          m.cls,
          None,
          current.taskSegments,
          cache = cache
        ).toOption.get.map(
          _.taskSegments.value.last
        )

      case _ => Set[Segment]()
    }

    NotFound(querySoFar, Set(current), next, possibleNexts.toSet)
  }
}
