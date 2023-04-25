package mill.main

import mill.define.ParseArgs.TargetsWithParams
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
  def resolveNonEmpty(
      selector: List[Segment],
      current: BaseModule,
      discover: Discover[_],
      args: Seq[String]
  ) = {
    ResolveNonEmpty.resolveNonEmpty(selector, current, discover, args).map { value => value.map(_.segments) }
  }
}

object ResolveMetadata extends Resolve[String] {
  def resolveNonEmpty(
      selector: List[Segment],
      current: BaseModule,
      discover: Discover[_],
      args: Seq[String]
  ) = {
    ResolveNonEmpty.resolveNonEmpty(selector, current, discover, args).map { value => value.map(_.segments.render) }
  }
}

object ResolveTasks extends Resolve[NamedTask[Any]] {
  def resolveNonEmpty(
      selector: List[Segment],
      current: BaseModule,
      discover: Discover[_],
      args: Seq[String]
  ) = {
    ResolveNonEmpty.resolveNonEmpty(selector, current, discover, args).flatMap { value =>
      val taskList: Set[Either[String, NamedTask[_]]] = value.collect {
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
      else Left(s"Cannot find default task to evaluate for module ${Segments(selector).render}")
    }

  }
}

trait Resolve[T] {
  def resolveNonEmpty(
      selector: List[Segment],
      current: BaseModule,
      discover: Discover[_],
      args: Seq[String]
  ): Either[String, Set[T]]

  def resolveTasks(
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[T]] = {
    val parsedGroups: Either[String, Seq[TargetsWithParams]] = ParseArgs(scriptArgs, selectMode)
    val resolvedGroups = parsedGroups.flatMap { groups =>
      val resolved = groups.map { case (selectors, args)  =>
        val selected = selectors.map { case (scopedSel, sel) =>
          for (rootModule <- resolveRootModule(evaluator, scopedSel))
          yield try {
            // We inject the `evaluator.rootModule` into the TargetScopt, rather
            // than the `rootModule`, because even if you are running an external
            // module we still want you to be able to resolve targets from your
            // main build. Resolving targets from external builds as CLI arguments
            // is not currently supported
            mill.eval.Evaluator.currentEvaluator.set(evaluator)
            resolveNonEmpty(
              sel.value.toList,
              rootModule,
              rootModule.millDiscover,
              args
            )
          } finally {
            mill.eval.Evaluator.currentEvaluator.set(null)
          }
        }
        for {
          taskss <- EitherOps.sequence(selected).map(_.toList)
          res <- EitherOps.sequence(taskss)
        } yield res.flatten
      }
      EitherOps.sequence(resolved)
    }
    resolvedGroups.map(_.flatten.toList)
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

  def resolveTasks[R](
      resolver: mill.main.Resolve[R],
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[R]] = {
    val parsedGroups: Either[String, Seq[TargetsWithParams]] = ParseArgs(scriptArgs, selectMode)
    val resolvedGroups = parsedGroups.flatMap { groups =>
      val resolved = groups.map { parsed: TargetsWithParams =>
        resolveTasks(resolver, evaluator, Right(parsed))
      }
      EitherOps.sequence(resolved)
    }
    resolvedGroups.map(_.flatten.toList)
  }

  private def resolveTasks[R](
      resolver: mill.main.Resolve[R],
      evaluator: Evaluator,
      targetsWithParams: Either[String, TargetsWithParams]
  ): Either[String, List[R]] = {
    for {
      parsed <- targetsWithParams
      (selectors, args) = parsed
      taskss <- {
        val selected = selectors.map { case (scopedSel, sel) =>
          for (rootModule <- resolveRootModule(evaluator, scopedSel))
            yield {

              try {
                // We inject the `evaluator.rootModule` into the TargetScopt, rather
                // than the `rootModule`, because even if you are running an external
                // module we still want you to be able to resolve targets from your
                // main build. Resolving targets from external builds as CLI arguments
                // is not currently supported
                mill.eval.Evaluator.currentEvaluator.set(evaluator)
                resolver.resolveNonEmpty(
                  sel.value.toList,
                  rootModule,
                  rootModule.millDiscover,
                  args
                )
              } finally {
                mill.eval.Evaluator.currentEvaluator.set(null)
              }
            }
        }
        EitherOps.sequence(selected).map(_.toList)
      }
      res <- EitherOps.sequence(taskss)
    } yield res.flatten
  }
}
