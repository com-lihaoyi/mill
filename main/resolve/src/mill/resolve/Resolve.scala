package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.define.{
  BaseModule,
  BaseModuleTree,
  Command,
  Discover,
  Module,
  NamedTask,
  Reflect,
  Segments,
  Target,
  TaskModule
}
import mill.resolve.ResolveCore.{Resolved, makeResultException}
import mill.util.EitherOps

object Resolve {
  object Segments extends Resolve[Segments] {
    private[mill] def handleResolved(
        baseModules: BaseModuleTree,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean
    ) = {
      Right(resolved.map(_.segments))
    }

    private[mill] override def deduplicate(items: List[Segments]): List[Segments] = items.distinct
  }

  object Tasks extends Resolve[NamedTask[Any]] {
    private[mill] def handleResolved(
        baseModules: BaseModuleTree,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean,
        allowPositionalCommandArgs: Boolean
    ) = {
      val taskList = resolved.map {
        case r: Resolved.Target =>
          val instantiated = ResolveCore
            .instantiateModule(baseModules, r.segments.init)
            .flatMap(instantiateTarget(r, _))

          instantiated.map(Some(_))

        case r: Resolved.Command =>
          val instantiated = ResolveCore
            .instantiateModule0(baseModules, r.segments.init)
            .flatMap { case (mod, rootMod) =>
              instantiateCommand(
                rootMod,
                r,
                mod,
                args,
                nullCommandDefaults,
                allowPositionalCommandArgs
              )
            }

          instantiated.map(Some(_))

        case r: Resolved.Module =>
          ResolveCore.instantiateModule(baseModules, r.segments).flatMap {
            case value: TaskModule =>
              val directChildrenOrErr = ResolveCore.resolveDirectChildren(
                baseModules,
                value.getClass,
                Some(value.defaultCommandName()),
                value.millModuleSegments
              )

              directChildrenOrErr.flatMap(directChildren =>
                directChildren.head match {
                  case r: Resolved.Target => instantiateTarget(r, value).map(Some(_))
                  case r: Resolved.Command =>
                    instantiateCommand(
                      baseModules.rootModule,
                      r,
                      value,
                      args,
                      nullCommandDefaults,
                      allowPositionalCommandArgs
                    ).map(Some(_))
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

  private def instantiateTarget(r: Resolved.Target, p: Module): Either[String, Target[_]] = {
    val definition = Reflect
      .reflect(p.getClass, classOf[Target[_]], _ == r.segments.parts.last, true)
      .head

    ResolveCore.catchWrapException(
      definition.invoke(p).asInstanceOf[Target[_]]
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
        r.segments.parts.last,
        rootModule.millDiscover.asInstanceOf[Discover[mill.define.Module]],
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
      discover: Discover[mill.define.Module],
      rest: Seq[String],
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ): Iterable[Either[String, Command[_]]] = for {
    (cls, (names, entryPoints)) <- discover.value
    if cls.isAssignableFrom(target.getClass)
    ep <- entryPoints
    if ep.name == name
  } yield {
    def withNullDefault(a: mainargs.ArgSig): mainargs.ArgSig = {
      if (a.default.nonEmpty) a
      else if (nullCommandDefaults) {
        a.copy(default =
          if (a.reader.isInstanceOf[SimpleTaskTokenReader[_]]) Some(_ => Target.task(null))
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
    ).flatMap { (grouped: TokenGrouping[_]) =>
      val mainData = ep.asInstanceOf[MainData[Any, Any]]
      val mainDataWithDefaults = mainData
        .copy(argSigs0 = mainData.argSigs0.map(withNullDefault))

      mainargs.Invoker.invoke(
        target,
        mainDataWithDefaults,
        grouped.asInstanceOf[TokenGrouping[Any]]
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
    }
  }
}

trait Resolve[T] {
  private[mill] def handleResolved(
      baseModules: BaseModuleTree,
      resolved: Seq[Resolved],
      args: Seq[String],
      segments: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ): Either[String, Seq[T]]

  def resolve(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false
  ): Either[String, List[T]] = {
    resolve0(Seq(rootModule), scriptArgs, selectMode, allowPositionalCommandArgs)
  }
  def resolve(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    resolve0(Seq(rootModule), scriptArgs, selectMode, false)
  }
  def resolve(
      rootModules: Seq[BaseModule],
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean
  ): Either[String, List[T]] = {
    resolve0(rootModules, scriptArgs, selectMode, allowPositionalCommandArgs)
  }
  def resolve(
      rootModules: Seq[BaseModule],
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    resolve0(rootModules, scriptArgs, selectMode, false)
  }

  private[mill] def resolve0(
      baseModules: Seq[BaseModule],
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean
  ): Either[String, List[T]] = {
    val nullCommandDefaults = selectMode == SelectMode.Multi
    val resolvedGroups = ParseArgs(scriptArgs, selectMode).flatMap { groups =>
      val resolved = groups.map { case (selectors, args) =>
        val selected = selectors.map { case (scopedSel, sel) =>
          resolveRootModule(baseModules, scopedSel).map { rootModuleSels =>
            resolveNonEmptyAndHandle(
              args,
              rootModuleSels,
              sel,
              nullCommandDefaults,
              allowPositionalCommandArgs
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
      baseModules: BaseModuleTree,
      sel: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ): Either[String, Seq[T]] = {
    val rootResolved = ResolveCore.Resolved.Module(Segments(), baseModules.rootModule.getClass)
    val resolved =
      ResolveCore.resolve(
        baseModules = baseModules,
        remainingQuery = sel.value.toList,
        current = rootResolved,
        querySoFar = Segments()
      ) match {
        case ResolveCore.Success(value) => Right(value)
        case ResolveCore.NotFound(segments, found, next, possibleNexts) =>
          val allPossibleNames = baseModules.allPossibleNames
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
      .map(_.toSeq.sortBy(_.segments.render))
      .flatMap(handleResolved(
        baseModules,
        _,
        args,
        sel,
        nullCommandDefaults,
        allowPositionalCommandArgs
      ))
  }

  private[mill] def deduplicate(items: List[T]): List[T] = items

  private[mill] def resolveRootModule(
      rootModules: Seq[BaseModule],
      scopedSel: Option[Segments]
  ): Either[String, BaseModuleTree] = {
    scopedSel match {
      case None => Right(BaseModuleTree.from(rootModules))

      case Some(scoping) =>
        for {
          moduleCls <-
            try Right(rootModules.head.getClass.getClassLoader.loadClass(scoping.render + "$"))
            catch {
              case e: ClassNotFoundException =>
                Left("Cannot resolve external module " + scoping.render)
            }
          rootModule <- moduleCls.getField("MODULE$").get(moduleCls) match {
            case rootModule: BaseModule => Right(rootModule)
            case _ => Left("Class " + scoping.render + " is not an BaseModule")
          }
        } yield new BaseModuleTree(Seq((Nil, rootModule)))
    }
  }
}
