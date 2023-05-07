package mill.resolve

import mainargs.{MainData, TokenGrouping}
import mill.define.{
  BaseModule,
  Command,
  Discover,
  ExternalModule,
  NamedTask,
  Segments,
  Target,
  TaskModule
}
import mill.resolve.ResolveCore.{Resolved, makeResultException}
import mill.util.EitherOps
object Resolve {
  object Segments extends Resolve[Segments] {
    def handleResolved(
        resolved: Seq[Resolved],
        discover: Discover[_],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean
    ) = {
      Right(resolved.map(_.segments))
    }
  }

  object Metadata extends Resolve[String] {
    def handleResolved(
        resolved: Seq[Resolved],
        discover: Discover[_],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean
    ) = {
      Right(resolved.map(_.segments.render))
    }
  }

  object Tasks extends Resolve[NamedTask[Any]] {
    def handleResolved(
        resolved: Seq[Resolved],
        discover: Discover[_],
        args: Seq[String],
        selector: Segments,
        nullCommandDefaults: Boolean
    ) = {

      val taskList: Seq[Either[String, NamedTask[_]]] = resolved
        .flatMap {
          case r: Resolved.Target => Some(r.valueOrErr())
          case r: Resolved.Command => Some(invokeCommand(
              r.parent,
              r.segments.parts.last,
              discover,
              args,
              nullCommandDefaults
            ))
          case r: Resolved.Module =>
            r.valueOrErr().toOption.collect { case value: TaskModule =>
              ResolveCore.resolveDirectChildren(
                value,
                Some(value.defaultCommandName()),
                value.millModuleSegments
              ).head match {
                case r: Resolved.Target => r.valueOrErr()
                case r: Resolved.Command => invokeCommand(
                    r.parent,
                    r.segments.parts.last,
                    discover,
                    args,
                    nullCommandDefaults
                  )
              }
            }
        }

      if (taskList.nonEmpty) EitherOps.sequence(taskList)
      else Left(s"Cannot find default task to evaluate for module ${selector.render}")
    }
  }

  private def invokeCommand(
      obj: mill.define.Module,
      name: String,
      discover: Discover[_],
      args: Seq[String],
      nullCommandDefaults: Boolean
  ) = ResolveCore.catchReflectException(
    invokeCommand0(
      obj,
      name,
      discover.asInstanceOf[Discover[mill.define.Module]],
      args,
      nullCommandDefaults
    ).head
  ).flatten

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
    if ep._2.name == name
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
      ._2
      .flattenedArgSigs
      .map { case (arg, term) => (withNullDefault(arg), term) }

    mainargs.TokenGrouping.groupArgs(
      rest,
      flattenedArgSigsWithDefaults,
      allowPositional = true,
      allowRepeats = false,
      allowLeftover = ep._2.argSigs0.exists(_.reader.isLeftover)
    ).flatMap { (grouped: TokenGrouping[_]) =>
      val mainData = ep._2.asInstanceOf[MainData[_, Any]]
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
            ep._2,
            f,
            totalWidth = 100,
            printHelpOnError = true,
            docsOnNewLine = false,
            customName = None,
            customDoc = None
          )
        )
    }
  }

}
trait Resolve[T] {
  def handleResolved(
      resolved: Seq[Resolved],
      discover: Discover[_],
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
  def resolve0(
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

    resolvedGroups.map(_.flatten.toList)
  }

  def resolveNonEmptyAndHandle(
      args: Seq[String],
      sel: Segments,
      rootModule: BaseModule,
      nullCommandDefaults: Boolean
  ): Either[String, Seq[T]] = {
    ResolveNonEmpty
      .resolveNonEmpty(sel.value.toList, rootModule)
      .map(_.toSeq.sortBy(_.segments.render))
      .flatMap(handleResolved(_, rootModule.millDiscover, args, sel, nullCommandDefaults))
  }

  def resolveRootModule(rootModule: BaseModule, scopedSel: Option[Segments]) = {
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
