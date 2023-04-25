package mill.main

import mill.define.{
  BaseModule,
  Discover,
  ExternalModule,
  NamedTask,
  ParseArgs,
  Segment,
  Segments,
  SelectMode,
  TaskModule
}
import mill.eval.Evaluator
import mill.main.ResolveCore.Resolved
import mill.util.EitherOps

object ResolveSegments extends Resolve[Segments] {
  def handleResolved(resolved: Set[Resolved],
                     discover: Discover[_],
                     args: Seq[String],
                     selector: Segments) = {
    Right(resolved.map(_.segments))
  }
}

object ResolveMetadata extends Resolve[String] {
  def handleResolved(resolved: Set[Resolved],
                     discover: Discover[_],
                     args: Seq[String],
                     selector: Segments) = {
    Right(resolved.map(_.segments.render))
  }
}

object ResolveTasks extends Resolve[NamedTask[Any]] {
  def handleResolved(resolved: Set[Resolved],
                     discover: Discover[_],
                     args: Seq[String],
                     selector: Segments) = {

    val taskList: Set[Either[String, NamedTask[_]]] = resolved.collect {
      case Resolved.Target(value) => Right(value)
      case Resolved.Command(value) => value()
      case Resolved.Module(value: TaskModule) =>
        ResolveCore.resolveDirectChildren(
          value,
          Some(value.defaultCommandName()),
          discover,
          args
        ).values.head.flatMap {
          case Resolved.Target(value) => Right(value)
          case Resolved.Command(value) => value()
        }
    }

    if (taskList.nonEmpty) EitherOps.sequence(taskList).map(_.toSet[NamedTask[Any]])
    else Left(s"Cannot find default task to evaluate for module ${selector.render}")
  }
}

trait Resolve[T] {
  def handleResolved(resolved: Set[Resolved],
                     discover: Discover[_],
                     args: Seq[String],
                     segments: Segments): Either[String, Set[T]]

  def resolveTasks(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    val resolvedGroups = ParseArgs(scriptArgs, selectMode).flatMap { groups =>
      val resolved = groups.map { case (selectors, args) =>
        val selected = selectors.map { case (scopedSel, sel) =>
          resolveRootModule(evaluator, scopedSel).map{rootModule =>
            try {
              // We inject the `evaluator.rootModule` into the TargetScopt, rather
              // than the `rootModule`, because even if you are running an external
              // module we still want you to be able to resolve targets from your
              // main build. Resolving targets from external builds as CLI arguments
              // is not currently supported
              mill.eval.Evaluator.currentEvaluator.set(evaluator)

              resolveNonEmptyAndHandle(args, sel, rootModule)
            } finally {
              mill.eval.Evaluator.currentEvaluator.set(null)
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

  def resolveNonEmptyAndHandle(args: Seq[String], sel: Segments, rootModule: BaseModule): Either[String, Set[T]] = {
    ResolveNonEmpty.resolveNonEmpty(sel.value.toList, rootModule, rootModule.millDiscover, args)
      .flatMap(handleResolved(_, rootModule.millDiscover, args, sel))
  }

  def resolveRootModule(evaluator: Evaluator, scopedSel: Option[Segments]) = {
    scopedSel match {
      case None => Right(evaluator.rootModule)
      case Some(scoping) =>
        for {
          moduleCls <-
            try Right(evaluator.rootModule.getClass.getClassLoader.loadClass(scoping.render + "$"))
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
