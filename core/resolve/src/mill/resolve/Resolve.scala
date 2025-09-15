package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.api.internal.{Reflect, Resolved, RootModule0}
import mill.api.{DefaultTaskModule, Discover, ExternalModule, Module, Result, Segments, SelectMode, SimpleTaskTokenReader, Task}
import mill.resolve.ResolveCore.makeResultException

private[mill] object Resolve {
  object Segments extends Resolve[Segments] {
    private[mill] def handleResolved(
        rootModule: RootModule0,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        resolveToModuleTasks: Boolean,
        cache: ResolveCore.Cache
    ) = {
      Result.Success(resolved.map(_.segments))
    }

    private[mill] override def deduplicate(items: List[Segments]): List[Segments] = items.distinct
  }

  object Raw extends Resolve[Resolved] {
    private[mill] def handleResolved(
        rootModule: RootModule0,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        resolveToModuleTasks: Boolean,
        cache: ResolveCore.Cache
    ) = {
      Result.Success(resolved)
    }

    private[mill] override def deduplicate(items: List[Resolved]): List[Resolved] = items.distinct
  }

  object Inspect extends Resolve[Either[Module, Task.Named[Any]]] {
    private[mill] def handleResolved(
        rootModule: RootModule0,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        resolveToModuleTasks: Boolean,
        cache: ResolveCore.Cache
    ) = {

      val taskList: Seq[Result[Either[Module, Option[Task.Named[?]]]]] = resolved.map {
        case m: Resolved.Module =>
          ResolveCore.instantiateModule(rootModule, m.segments, cache).map(Left(_))

        case t =>
          Resolve
            .Tasks
            .handleTask(rootModule, args, nullCommandDefaults, allowPositionalCommandArgs, cache, t)
            .map(Right(_))
      }

      val sequenced = Result.sequence(taskList)

      sequenced.map(flattened =>
        flattened.flatMap {
          case Left(m) => Some(Left(m))
          case Right(None) => None
          case Right(Some(t)) => Some(Right(t))
        }
      )
    }

  }

  object Tasks extends Resolve[Task.Named[Any]] {
    private[Resolve] def handleTask(
        rootModule: RootModule0,
        args: Seq[String],
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        cache: ResolveCore.Cache,
        task: Resolved
    ) = task match {
      case r: Resolved.NamedTask =>
        val instantiated = ResolveCore
          .instantiateModule(rootModule, r.segments.init, cache)
          .flatMap(instantiateNamedTask(r, _, cache))
        instantiated.map(Some(_))

      case r: Resolved.Command =>
        val instantiated = ResolveCore
          .instantiateModule(rootModule, r.segments.init, cache)
          .flatMap { mod =>
            instantiateCommand(
              rootModule,
              r,
              mod,
              args,
              nullCommandDefaults,
              allowPositionalCommandArgs
            )
          }
        instantiated.map(Some(_))

      case r: Resolved.Module =>
        ResolveCore.instantiateModule(rootModule, r.segments, cache).flatMap {
          case value: DefaultTaskModule =>
            val directChildrenOrErr = ResolveCore.resolveDirectChildren(
              rootModule,
              value.getClass,
              Some(value.defaultTask()),
              value.moduleSegments,
              cache = cache
            )

            directChildrenOrErr.flatMap(directChildren =>
              directChildren.head match {
                case r: Resolved.NamedTask => instantiateNamedTask(r, value, cache).map(Some(_))
                case r: Resolved.Command =>
                  instantiateCommand(
                    rootModule,
                    r,
                    value,
                    args,
                    nullCommandDefaults,
                    allowPositionalCommandArgs
                  ).map(Some(_))
                case _ => ???
              }
            )
          case _ => Result.Success(None)
        }

    }
    private[mill] def handleResolved(
        rootModule: RootModule0,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        resolveToModuleTasks: Boolean,
        cache: ResolveCore.Cache
    ) = {

      val taskList: Seq[Result[Option[Task.Named[?]]]] = resolved.map(handleTask(
        rootModule,
        args,
        nullCommandDefaults,
        allowPositionalCommandArgs,
        cache,
        _
      ))

      val sequenced = Result.sequence(taskList).map(_.flatten)

      sequenced.flatMap(flattened =>
        if (flattened.nonEmpty) Result.Success(flattened)
        else Result.Failure(s"Cannot find default task to evaluate for module ${selector.render}")
      )
    }

    private[mill] override def deduplicate(items: List[Task.Named[Any]]): List[Task.Named[Any]] =
      items.distinctBy(_.ctx.segments)
  }

  private def instantiateNamedTask(
      r: Resolved.NamedTask,
      p: Module,
      cache: ResolveCore.Cache
  ): Result[Task.Named[?]] = {
    val definition = Reflect
      .reflect(
        p.getClass,
        classOf[Task.Named[?]],
        _ == r.segments.last.value,
        true,
        getMethods = cache.getMethods
      )
      .head

    ResolveCore.catchWrapException(
      definition.invoke(p).asInstanceOf[Task.Named[?]]
    )
  }

  private def instantiateCommand(
      rootModule: RootModule0,
      r: Resolved.Command,
      p: Module,
      args: Seq[String],
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ) = {
    ResolveCore.catchWrapException {
      val invoked = invokeCommand0(
        p,
        r.segments.last.value,
        rootModule.moduleCtx.discover,
        args,
        nullCommandDefaults,
        allowPositionalCommandArgs
      )

      invoked
    }.flatMap(x => x)
  }

  private def invokeCommand0(
      module: mill.api.Module,
      name: String,
      discover: Discover,
      rest: Seq[String],
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ): Result[Task.Command[?]] = {
    val ep = discover.resolveEntrypoint(module.getClass, name)
      .getOrElse(
        sys.error(
          s"Unable to resolve command $name on module $module of class ${module.getClass}"
        )
      )
    def withNullDefault(a: mainargs.ArgSig): mainargs.ArgSig = {
      if (a.default.nonEmpty) a
      else if (nullCommandDefaults) {
        a.copy(default =
          if (a.reader.isInstanceOf[SimpleTaskTokenReader[?]])
            Some(_ => mill.api.Task.Anon(null))
          else Some(_ => null)
        )
      } else a
    }

    val flattenedArgSigsWithDefaults = ep
      .flattenedArgSigs
      .map { case (arg, term) => (withNullDefault(arg), term) }

    mainargs.TokenGrouping.groupArgs(
      rest,
      flattenedArgSigsWithDefaults,
      allowPositional = allowPositionalCommandArgs,
      allowRepeats = false,
      allowLeftover = ep.argSigs0.exists(_.reader.isLeftover),
      nameMapper = mainargs.Util.kebabCaseNameMapper
    ).flatMap { (grouped: TokenGrouping[Any]) =>
      val mainData = ep.asInstanceOf[MainData[Any, Any]]
      val mainDataWithDefaults = mainData
        .copy(argSigs0 = mainData.argSigs0.map(withNullDefault))

      mainargs.Invoker.invoke(
        module,
        mainDataWithDefaults,
        grouped
      )
    } match {
      case mainargs.Result.Success(v: Task.Command[_]) => Result.Success(v)
      case mainargs.Result.Failure.Exception(e) =>
        Result.Failure(makeResultException(e, new Exception()).left.get)
      case f: mainargs.Result.Failure =>
        Result.Failure(
          mainargs.Renderer.renderResult(
            ep,
            f,
            totalWidth = 100,
            printHelpOnError = true,
            docsOnNewLine = false,
            customName = None,
            customDoc = None,
            sorted = true,
            nameMapper = mainargs.Util.nullNameMapper
          )
        )
      case _ => ???
    }
  }
}

private[mill] trait Resolve[T] {
  private[mill] def handleResolved(
      rootModule: RootModule0,
      resolved: Seq[Resolved],
      args: Seq[String],
      segments: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean,
      cache: ResolveCore.Cache
  ): Result[Seq[T]]

  private[mill] def resolve(
      rootModule: RootModule0,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false,
      scriptModuleResolver: String => Option[Result[mill.api.ExternalModule]]
  ): Result[List[T]] = {
    val nullCommandDefaults = selectMode == SelectMode.Multi
    val cache = new ResolveCore.Cache()
    def handleScriptModule(args: Seq[String], fallback: => Result[Seq[T]]): Result[Seq[T]] = {
      val (first, selector, remaining) = args match {
        case Seq(s"$prefix:$suffix", rest*) => (prefix, Some(suffix), rest)
        case Seq(head, rest*) => (head, None, rest)
      }

      def handleResolved(resolved: Result[ExternalModule], segments: Seq[String], remaining: Seq[String]) = {
        resolved.flatMap(scriptModule =>
          resolveNonEmptyAndHandle(
            remaining,
            scriptModule,
            Segments.labels(segments*),
            nullCommandDefaults,
            allowPositionalCommandArgs,
            resolveToModuleTasks
          )
        )
      }

      scriptModuleResolver(first) match {
        case Some(resolved) => handleResolved(resolved, selector.toSeq, remaining)
        case None =>
          if (selector.isEmpty) { // if the `:selector` is empty, try treating the `first` as a selector
            scriptModuleResolver(".") match {
              case Some(resolved) => handleResolved(resolved, Seq(first), remaining)
              case None => fallback
            }
          }
          else fallback
      }
    }
    val resolvedGroups = ParseArgs.separate(scriptArgs).map { group =>
      ParseArgs.extractAndValidate(group, selectMode == SelectMode.Multi) match {
        case f: Result.Failure => handleScriptModule(group, f)
        case Result.Success((selectors, args)) =>
          val selected: Seq[Result[Seq[T]]] = selectors.map { case (scopedSel, sel) =>
            resolveRootModule(rootModule, scopedSel) match {
              case f: Result.Failure => handleScriptModule(group, f)
              case Result.Success(rootModuleSels) =>
                val res =
                  resolveNonEmptyAndHandle1(rootModuleSels, sel.getOrElse(Segments()), cache)
                def notFoundResult = resolveNonEmptyAndHandle2(
                  rootModuleSels,
                  args,
                  sel.getOrElse(Segments()),
                  nullCommandDefaults,
                  allowPositionalCommandArgs,
                  resolveToModuleTasks,
                  cache,
                  res
                )
                res match {
                  case _: ResolveCore.NotFound => handleScriptModule(group, notFoundResult)
                  case res => notFoundResult
                }
            }
          }
          Result.sequence(selected).map(_.flatten)
      }
    }

    Result.sequence(resolvedGroups).map(_.flatten.toList).map(deduplicate)
  }

  private[mill] def resolveNonEmptyAndHandle(
      args: Seq[String],
      rootModule: RootModule0,
      sel: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean
  ): Result[Seq[T]] = {
    val cache = new ResolveCore.Cache()
    resolveNonEmptyAndHandle2(
      rootModule,
      args,
      sel,
      nullCommandDefaults,
      allowPositionalCommandArgs,
      resolveToModuleTasks,
      cache,
      resolveNonEmptyAndHandle1(rootModule, sel, cache)
    )
  }
  private[mill] def resolveNonEmptyAndHandle1(
      rootModule: RootModule0,
      sel: Segments,
      cache: ResolveCore.Cache
  ): ResolveCore.Result = {
    val rootResolved = Resolved.Module(Segments(), rootModule.getClass)
    ResolveCore.resolve(
      rootModule = rootModule,
      remainingQuery = sel.value.toList,
      current = rootResolved,
      querySoFar = Segments(),
      seenModules = Set.empty,
      cache = cache
    )
  }

  private[mill] def resolveNonEmptyAndHandle2(
      rootModule: RootModule0,
      args: Seq[String],
      sel: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean,
      cache: ResolveCore.Cache,
      result: ResolveCore.Result
  ): Result[Seq[T]] = {
    val resolved = result match {
      case ResolveCore.Success(value) => Result.Success(value)
      case ResolveCore.NotFound(segments, found, next, possibleNexts) =>
        val allPossibleNames = rootModule
          .moduleCtx
          .discover
          .classInfo
          .values
          .flatMap(_.declaredTasks)
          .map(_.name)
          .toSet

        Result.Failure(ResolveNotFoundHandler(
          selector = sel,
          segments = segments,
          found = found,
          next = next,
          possibleNexts = possibleNexts,
          allPossibleNames = allPossibleNames
        ))
      case ResolveCore.Error(value) => Result.Failure(value)
    }

    resolved.flatMap { r =>
      val sorted = r.sorted
      handleResolved(
        rootModule,
        sorted,
        args,
        sel,
        nullCommandDefaults,
        allowPositionalCommandArgs,
        resolveToModuleTasks,
        cache = cache
      )
    }
  }

  private[mill] def deduplicate(items: List[T]): List[T] = items

  private[mill] def resolveRootModule(
      rootModule: RootModule0,
      scopedSel: Option[Segments]
  ): Result[RootModule0] = {
    scopedSel match {
      case None => Result.Success(rootModule)

      case Some(scoping) =>
        for {
          moduleCls <-
            try Result.Success(rootModule.getClass.getClassLoader.loadClass(scoping.render + "$"))
            catch {
              case _: ClassNotFoundException =>
                try Result.Success(rootModule.getClass.getClassLoader.loadClass(
                    scoping.render + ".package$"
                  ))
                catch {
                  case _: ClassNotFoundException =>
                    Result.Failure("Cannot resolve external module " + scoping.render)
                }
            }
          rootModule <- moduleCls.getField("MODULE$").get(moduleCls) match {
            case alias: mill.api.ExternalModule.Alias => Result.Success(alias.value)
            case rootModule: RootModule0 => Result.Success(rootModule)
            case _ => Result.Failure("Class " + scoping.render + " is not an BaseModule")
          }
        } yield rootModule
    }
  }
}
