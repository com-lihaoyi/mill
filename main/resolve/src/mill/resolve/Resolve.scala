package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.define.{
  BaseModule,
  Command,
  Discover,
  ExternalModule,
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
        rootModule: BaseModule,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean
    ) = {
      Right(resolved.map(_.segments))
    }

    private[mill] def deduplicate(items: List[Segments]) = items.distinct
  }

  object Tasks extends Resolve[NamedTask[Any]] {
    private[mill] def handleResolved(
        rootModule: BaseModule,
        resolved: Seq[Resolved],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean
    ) = {
      val taskList = resolved.map {
        case r: Resolved.Target =>
          val instantiated = ResolveCore
            .instantiateModule(rootModule, r.segments.init)
            .flatMap(instantiateTarget(r, _))

          instantiated.map(Some(_))

        case r: Resolved.Command =>
          val instantiated = ResolveCore
            .instantiateModule(rootModule, r.segments.init)
            .flatMap(instantiateCommand(rootModule, r, _, args, nullCommandDefaults))

          instantiated.map(Some(_))

        case r: Resolved.Module =>
          ResolveCore.instantiateModule(rootModule, r.segments).flatMap {
            case value: TaskModule =>
              val directChildrenOrErr = ResolveCore.resolveDirectChildren(
                rootModule,
                value.getClass,
                Some(value.defaultCommandName()),
                value.millModuleSegments
              )

              directChildrenOrErr.flatMap(directChildren =>
                directChildren.head match {
                  case r: Resolved.Target => instantiateTarget(r, value).map(Some(_))
                  case r: Resolved.Command =>
                    instantiateCommand(rootModule, r, value, args, nullCommandDefaults).map(Some(_))
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

    private[mill] def deduplicate(items: List[NamedTask[Any]]) = items.distinctBy(_.ctx.segments)
  }

  private def instantiateTarget(r: Resolved.Target, p: Module) = {
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
      nullCommandDefaults: Boolean
  ) = {
    ResolveCore.catchWrapException(
      invokeCommand0(
        p,
        r.segments.parts.last,
        rootModule.millDiscover.asInstanceOf[Discover[mill.define.Module]],
        args,
        nullCommandDefaults
      ).head
    ).flatten
  }

  private def invokeCommand0(
      target: mill.define.Module,
      name: String,
      discover: Discover[mill.define.Module],
      rest: Seq[String],
      nullCommandDefaults: Boolean
  ): Iterable[Either[String, Command[_]]] = for {
    (cls, entryPoints) <- discover.value
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
      allowPositional = true,
      allowRepeats = false,
      allowLeftover = ep.argSigs0.exists(_.reader.isLeftover)
    ).flatMap { (grouped: TokenGrouping[_]) =>
      val mainData = ep.asInstanceOf[MainData[_, Any]]
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
            sorted = true
          )
        )
    }
  }
}

trait Resolve[T] {
  private[mill] def handleResolved(
      rootModule: BaseModule,
      resolved: Seq[Resolved],
      args: Seq[String],
      segments: Segments,
      nullCommandDefaults: Boolean
  ): Either[String, Seq[T]]

  def resolve(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    resolve0(rootModule, scriptArgs, selectMode)
  }

  private[mill] def resolve0(
      baseModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    val nullCommandDefaults = selectMode == SelectMode.Multi
    val resolvedGroups = ParseArgs(scriptArgs, selectMode).flatMap { groups =>
      val resolved = groups.map { case (selectors, args) =>
        val selected = selectors.map { case (scopedSel, sel) =>
          resolveRootModule(baseModule, scopedSel).map { rootModule =>
            resolveNonEmptyAndHandle(args, sel, rootModule, nullCommandDefaults)
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
      sel: Segments,
      rootModule: BaseModule,
      nullCommandDefaults: Boolean
  ): Either[String, Seq[T]] = {
    val rootResolved = ResolveCore.Resolved.Module(Segments(), rootModule.getClass)

    val resolved =
      ResolveCore.resolve(rootModule, sel.value.toList, rootResolved, Segments()) match {
        case ResolveCore.Success(value) => Right(value)
        case ResolveCore.NotFound(segments, found, next, possibleNexts) =>
          Left(ResolveNotFoundHandler(sel, segments, found, next, possibleNexts))
        case ResolveCore.Error(value) => Left(value)
      }

    resolved
      .map(_.toSeq.sortBy(_.segments.render))
      .flatMap(handleResolved(rootModule, _, args, sel, nullCommandDefaults))
  }

  private[mill] def deduplicate(items: List[T]): List[T]

  private[mill] def resolveRootModule(rootModule: BaseModule, scopedSel: Option[Segments]) = {
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
            case rootModule: ExternalModule => Right(rootModule)
            case _ => Left("Class " + scoping.render + " is not an external module")
          }
        } yield rootModule
    }
  }
}
