package mill.main

import mill.define.{BaseModule, Discover, ExternalModule, NamedTask, Segments, TaskModule}
import mill.eval.Evaluator
import mill.main.ResolveCore.Resolved
import mill.util.EitherOps

object ResolveSegments extends Resolve[Segments] {
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

object ResolveMetadata extends Resolve[String] {
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

object ResolveTasks extends Resolve[NamedTask[Any]] {
  def handleResolved(
      resolved: Seq[Resolved],
      discover: Discover[_],
      args: Seq[String],
      selector: Segments,
      nullCommandDefaults: Boolean
  ) = {

    val taskList: Seq[Either[String, NamedTask[_]]] = resolved
      .flatMap {
        case r: Resolved.Target => Some(r.valueOrErr)
        case r: Resolved.Command => Some(r.valueOrErr)
        case r: Resolved.Module =>
          r.valueOrErr.toOption.collect { case value: TaskModule =>
            ResolveCore.resolveDirectChildren(
              value,
              Some(value.defaultCommandName()),
              discover,
              args,
              value.millModuleSegments,
              nullCommandDefaults
            ).head match {
              case r: Resolved.Target => r.valueOrErr
              case r: Resolved.Command => r.valueOrErr
            }
          }
      }

    if (taskList.nonEmpty) EitherOps.sequence(taskList)
    else Left(s"Cannot find default task to evaluate for module ${selector.render}")
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
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    resolve0(Some(evaluator), evaluator.rootModule, scriptArgs, selectMode)
  }
  def resolve0(
      evaluatorOpt: Option[Evaluator],
      baseModule: BaseModule,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    val nullCommandDefaults = selectMode == SelectMode.Multi
    val resolvedGroups = ParseArgs(scriptArgs, selectMode).flatMap { groups =>
      val resolved = groups.map { case (selectors, args) =>
        val selected = selectors.map { case (scopedSel, sel) =>
          resolveRootModule(baseModule, scopedSel).map { rootModule =>
            evaluatorOpt match {
              case None => resolveNonEmptyAndHandle(args, sel, rootModule, nullCommandDefaults)
              case Some(eval) =>
                // We inject the `evaluator.rootModule` into the TargetScopt, rather
                // than the `rootModule`, because even if you are running an external
                // module we still want you to be able to resolve targets from your
                // main build. Resolving targets from external builds as CLI arguments
                // is not currently supported
                mill.eval.Evaluator.currentEvaluator.withValue(eval) {
                  resolveNonEmptyAndHandle(args, sel, rootModule, nullCommandDefaults)
                }
            }
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
      .resolveNonEmpty(sel.value.toList, rootModule, rootModule.millDiscover, args, nullCommandDefaults)
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
