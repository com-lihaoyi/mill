package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.define.internal.Reflect
import mill.define.{
  BaseModule,
  Discover,
  Module,
  Segments,
  SelectMode,
  SimpleTaskTokenReader,
  Task,
  TaskModule
}
import mill.api.Result
import mill.resolve.ResolveCore.{Resolved, makeResultException}

import scala.annotation.tailrec

private[mill] object Resolve {
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
      Result.Success(resolved.map(_.segments))
    }

    private[mill] override def deduplicate(items: List[Segments]): List[Segments] = items.distinct
  }

  object Inspect extends Resolve[Either[Module, Task.Named[Any]]] {
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
        rootModule: BaseModule,
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
          case value: TaskModule =>
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
          case _ => Result.Success(None)
        }

    }
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
        r.cls,
        rootModule.moduleCtx.discover,
        args,
        nullCommandDefaults,
        allowPositionalCommandArgs
      )

      invoked.head
    }.flatMap(x => x)
  }

  private def invokeCommand0(
      target: mill.define.Module,
      name: String,
      commandCls: Class[?],
      discover: Discover,
      rest: Seq[String],
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean
  ): Option[Result[Task.Command[?]]] = for {
    ep <- discover.resolveEntrypoint(commandCls, name)
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
      rootModule: BaseModule,
      resolved: Seq[Resolved],
      args: Seq[String],
      segments: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean,
      cache: ResolveCore.Cache
  ): Result[Seq[T]]

  def resolve(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): Result[List[T]] = {
    mill.constants.DebugLog.println("Resolve#resolve")
    resolve0(rootModule, scriptArgs, selectMode, allowPositionalCommandArgs, resolveToModuleTasks)
  }

  private[mill] def resolve0(
      rootModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean
  ): Result[List[T]] = {
    val nullCommandDefaults = selectMode == SelectMode.Multi

    val MaskPattern = """\\+\Q+\E""".r
    /**
     * Partition the arguments in groups using a separator.
     * To also use the separator as argument, masking it with a backslash (`\`) is supported.
     */
    @tailrec
    def separated(result: Seq[Seq[String]], rest: Seq[String]): Seq[Seq[String]] = rest match {
      case Seq() => if (result.nonEmpty) result else Seq(Seq())
      case r =>
        val (next, r2) = r.span(_ != "+")
        separated(
          result ++ Seq(next.map {
            case x@MaskPattern(_*) => x.drop(1)
            case x => x
          }),
          r2.drop(1)
        )
    }

    @tailrec def recurse(remainingArgs: List[String], allResults: List[T]): Result[List[T]] =
      remainingArgs match {
        case first :: rest =>
          val result = for {
            (scopedSel, sel) <- ParseArgs.extractSegments(first)
            rootModuleSels <- resolveRootModule(rootModule, scopedSel)
            (items, foundCommands) <- resolveNonEmptyAndHandle(
              Nil,
              rootModuleSels,
              sel.getOrElse(Segments()),
              nullCommandDefaults = true,
              allowPositionalCommandArgs,
              resolveToModuleTasks
            )
            result0 <- {
              val foundPositionalCommands = foundCommands.exists(c =>
                allowPositionalCommandArgs ||
                  rootModule.millDiscover2
                    .resolveEntrypoint(c.cls, c.segments.last.value)
                    .exists(_.argSigs0.exists(sig => sig.positional || sig.reader.isLeftover))
              )
              // If there are no commands, or there are non-positional commands the next token
              // starts with a `-` and cannot be passed to those commands, then we can safely
              // say that only a single token is relevant
              if (foundCommands.isEmpty || !foundPositionalCommands && rest.headOption.exists(_.startsWith("-"))) {
                Result.Success(Left(items))
              } else {
                resolveNonEmptyAndHandle(
                  rest,
                  rootModuleSels,
                  sel.getOrElse(Segments()),
                  nullCommandDefaults,
                  allowPositionalCommandArgs,
                  resolveToModuleTasks
                ).map { t =>
                  Right(t._1.toList)
                }
              }
            }
          } yield result0

          result match {
            case Result.Success(Left(items)) => recurse(rest, allResults ::: items.toList)
            case Result.Success(Right(cmds)) => Result.Success(allResults ::: cmds)
            case Result.Failure(msg) => Result.Failure(msg)
          }

        case _ => allResults
      }

    Result.sequence(separated(Nil, scriptArgs).flatten.map(ExpandBraces.expandBraces))
      .map(_.flatten)
      .flatMap(args => recurse(args.toList, Nil))
  }

  private[mill] def resolveNonEmptyAndHandle(
      args: Seq[String],
      rootModule: BaseModule,
      sel: Segments,
      nullCommandDefaults: Boolean,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean
  ): Result[(Seq[T], Seq[Resolved.Command])] = {
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
        ).map { r2 =>
          r2 -> r.collect { case c: Resolved.Command => c }
        }
      )
  }

  private[mill] def deduplicate(items: List[T]): List[T] = items

  private[mill] def resolveRootModule(
      rootModule: BaseModule,
      scopedSel: Option[Segments]
  ): Result[BaseModule] = {
    scopedSel match {
      case None => Result.Success(rootModule)

      case Some(scoping) =>
        for {
          moduleCls <-
            try Result.Success(rootModule.getClass.getClassLoader.loadClass(scoping.render + "$"))
            catch {
              case e: ClassNotFoundException =>
                try Result.Success(rootModule.getClass.getClassLoader.loadClass(
                    scoping.render + ".package$"
                  ))
                catch {
                  case e: ClassNotFoundException =>
                    Result.Failure("Cannot resolve external module " + scoping.render)
                }
            }
          rootModule <- moduleCls.getField("MODULE$").get(moduleCls) match {
            case alias: mill.define.ExternalModule.Alias => Result.Success(alias.value)
            case rootModule: BaseModule => Result.Success(rootModule)
            case _ => Result.Failure("Class " + scoping.render + " is not an BaseModule")
          }
        } yield rootModule
    }
  }
}
