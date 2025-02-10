package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.define.internal.Reflect
import mill.define.{
  BaseModule,
  Command,
  Discover,
  Module,
  ModuleTask,
  NamedTask,
  Segments,
  TaskModule
}
import mill.internal.EitherOps
import mill.resolve.ResolveCore.{Resolved, makeResultException}

object Resolve {
  object Segments extends Resolve[Segments] {
    private[mill] def handleResolved(
        rootModule: BaseModule,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        resolveToModuleTasks: Boolean,
        cache: ResolveCore.Cache
    ) = {
      Right(resolved.map(_.segments))
    }

    private[mill] override def deduplicate(items: List[Segments]): List[Segments] = items.distinct
  }

  object Tasks extends Resolve[NamedTask[Any]] {
    private[mill] def handleResolved(
        rootModule: BaseModule,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean,
        resolveToModuleTasks: Boolean,
        cache: ResolveCore.Cache
    ) = {

      val taskList = resolved.map {
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
            case value if resolveToModuleTasks => Right(Some(ModuleTask(value)))
            case value: TaskModule if !resolveToModuleTasks =>
              val directChildrenOrErr = ResolveCore.resolveDirectChildren(
                rootModule,
                value.getClass,
                Some(value.defaultCommandName()),
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
            case _ => Right(None)
          }
      }

      val sequenced = EitherOps.sequence(taskList).map(_.flatten)

      sequenced.flatMap(flattened =>
        if (flattened.nonEmpty) Right(flattened)
        else Left(s"Cannot find default task to evaluate for module ${selector.render}")
      )
    }

    private[mill] override def deduplicate(items: List[NamedTask[Any]]): List[NamedTask[Any]] =
      items.distinctBy(_.ctx.segments)
  }

  private def instantiateNamedTask(
      r: Resolved.NamedTask,
      p: Module,
      cache: ResolveCore.Cache
  ): Either[String, NamedTask[?]] = {
    val definition = Reflect
      .reflect(
        p.getClass,
        classOf[NamedTask[?]],
        _ == r.segments.last.value,
        true,
        getMethods = cache.getMethods
      )
      .head

    ResolveCore.catchWrapException(
      definition.invoke(p).asInstanceOf[NamedTask[?]]
    )
  }

  private def instantiateCommand(
      rootModule: BaseModule,
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

      invoked.head
    }.flatten
  }

  private def invokeCommand0(
      target: mill.define.Module,
      name: String,
      discover: Discover,
      rest: Seq[String],
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ): Option[Either[String, Command[?]]] = for {
    ep <- discover.resolveEntrypoint(target.getClass, name)
  } yield {
    def withNullDefault(a: mainargs.ArgSig): mainargs.ArgSig = {
      if (a.default.nonEmpty) a
      else if (nullCommandDefaults) {
        a.copy(default =
          if (a.reader.isInstanceOf[SimpleTaskTokenReader[?]])
            Some(_ => mill.define.Task.Anon(null))
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
        target,
        mainDataWithDefaults,
        grouped
      )
    } match {
      case mainargs.Result.Success(v: Command[_]) => Right(v)
      case mainargs.Result.Failure.Exception(e) => makeResultException(e, new Exception())
      case f: mainargs.Result.Failure =>
        Left(
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

trait Resolve[T] {
  private[mill] def handleResolved(
      rootModule: BaseModule,
      resolved: Seq[Resolved],
      args: Seq[String],
      segments: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean,
      cache: ResolveCore.Cache
  ): Either[String, Seq[T]]

  def resolve(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): Either[String, List[T]] = {
    resolve0(rootModule, scriptArgs, selectMode, allowPositionalCommandArgs, resolveToModuleTasks)
  }

  private[mill] def resolve0(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean
  ): Either[String, List[T]] = {
    val nullCommandDefaults = selectMode == SelectMode.Multi
    val resolvedGroups = ParseArgs(scriptArgs, selectMode).flatMap { groups =>
      val resolved = groups.map { case (selectors, args) =>
        val selected = selectors.map { case (scopedSel, sel) =>
          resolveRootModule(rootModule, scopedSel).map { rootModuleSels =>
            resolveNonEmptyAndHandle(
              args,
              rootModuleSels,
              sel.getOrElse(Segments()),
              nullCommandDefaults,
              allowPositionalCommandArgs,
              resolveToModuleTasks
            )
          }
        }

        EitherOps
          .sequence(selected)
          .flatMap(EitherOps.sequence(_))
          .map(_.flatten)
      }

      EitherOps.sequence(resolved)
    }

    resolvedGroups.map(_.flatten.toList).map(deduplicate)
  }

  private[mill] def resolveNonEmptyAndHandle(
      args: Seq[String],
      rootModule: BaseModule,
      sel: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean
  ): Either[String, Seq[T]] = {
    val rootResolved = ResolveCore.Resolved.Module(Segments(), rootModule.getClass)
    val cache = new ResolveCore.Cache()
    val resolved =
      ResolveCore.resolve(
        rootModule = rootModule,
        remainingQuery = sel.value.toList,
        current = rootResolved,
        querySoFar = Segments(),
        seenModules = Set.empty,
        cache = cache
      ) match {
        case ResolveCore.Success(value) => Right(value)
        case ResolveCore.NotFound(segments, found, next, possibleNexts) =>
          val allPossibleNames = rootModule
            .moduleCtx
            .discover
            .classInfo
            .values
            .flatMap(_.declaredTasks)
            .map(_.name)
            .toSet

          Left(ResolveNotFoundHandler(
            selector = sel,
            segments = segments,
            found = found,
            next = next,
            possibleNexts = possibleNexts,
            allPossibleNames = allPossibleNames
          ))
        case ResolveCore.Error(value) => Left(value)
      }

    resolved
      .flatMap(r =>
        handleResolved(
          rootModule,
          r.sortBy(_.segments),
          args,
          sel,
          nullCommandDefaults,
          allowPositionalCommandArgs,
          resolveToModuleTasks,
          cache = cache
        )
      )
  }

  private[mill] def deduplicate(items: List[T]): List[T] = items

  private[mill] def resolveRootModule(
      rootModule: BaseModule,
      scopedSel: Option[Segments]
  ): Either[String, BaseModule] = {
    scopedSel match {
      case None => Right(rootModule)

      case Some(scoping) =>
        for {
          moduleCls <-
            try Right(rootModule.getClass.getClassLoader.loadClass(scoping.render + "$"))
            catch {
              case e: ClassNotFoundException =>
                Left("Cannot resolve external module " + scoping.render)
            }
          rootModule <- moduleCls.getField("MODULE$").get(moduleCls) match {
            case rootModule: BaseModule => Right(rootModule)
            case _ => Left("Class " + scoping.render + " is not an BaseModule")
          }
        } yield rootModule
    }
  }
}
