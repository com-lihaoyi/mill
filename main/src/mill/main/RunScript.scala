package mill.main

import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.{EitherOps, Watched}
import mill.define.SelectMode
import mill.define.ParseArgs
import mill.api.{PathRef, Result}
import mill.api.Strict.Agg
import scala.reflect.ClassTag
import mill.define.ParseArgs.TargetsWithParams

/**
 * Custom version of ammonite.main.Scripts, letting us run the build.sc script
 * directly without going through Ammonite's main-method/argument-parsing
 * subsystem
 */
object RunScript {

  type TaskName = String

  def resolveTasks[T, R: ClassTag](
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

  private def resolveTasks[T, R: ClassTag](
      resolver: mill.main.Resolve[R],
      evaluator: Evaluator,
      targetsWithParams: Either[String, TargetsWithParams]
  ): Either[String, List[R]] = {
    for {
      parsed <- targetsWithParams
      (selectors, args) = parsed
      taskss <- {
        val selected = selectors.map { case (scopedSel, sel) =>
          for (res <- prepareResolve(evaluator, scopedSel, sel))
            yield {
              val (rootModule, crossSelectors) = res

              try {
                // We inject the `evaluator.rootModule` into the TargetScopt, rather
                // than the `rootModule`, because even if you are running an external
                // module we still want you to be able to resolve targets from your
                // main build. Resolving targets from external builds as CLI arguments
                // is not currently supported
                mill.eval.Evaluator.currentEvaluator.set(evaluator)
                resolver.resolve(
                  sel.value.toList,
                  rootModule,
                  rootModule.millDiscover,
                  args,
                  crossSelectors.toList
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

  def resolveRootModule[T](evaluator: Evaluator, scopedSel: Option[Segments]) = {
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

  def prepareResolve[T](
      evaluator: Evaluator,
      scopedSel: Option[Segments],
      sel: Segments
  ): Either[String, (BaseModule, Seq[List[String]])] = {
    for (rootModule <- resolveRootModule(evaluator, scopedSel))
      yield {
        val crossSelectors = sel.value.map {
          case Segment.Cross(x) => x.toList.map(_.toString)
          case _ => Nil
        }
        (rootModule, crossSelectors)
      }
  }


  def evaluateTasks[T](
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]])] = {
    for (targets <- resolveTasks(mill.main.ResolveTasks, evaluator, scriptArgs, selectMode))
      yield {
        val (watched, res) = evaluate(evaluator, Agg.from(targets.distinct))

        val watched2 = for {
          x <- res.toSeq
          (Watched(_, extraWatched), _) <- x
          w <- extraWatched
        } yield w

        (watched ++ watched2, res)
      }
  }

  def evaluateTasksNamed[T](
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, (Seq[PathRef], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]])] = {
    for (targets <- resolveTasks(mill.main.ResolveTasks, evaluator, scriptArgs, selectMode))
      yield {
        val (watched, res) = evaluateNamed(evaluator, Agg.from(targets.distinct))

        val watched2 = for {
          x <- res.toSeq
          (Watched(_, extraWatched), _) <- x
          w <- extraWatched
        } yield w

        (watched ++ watched2, res)
      }
  }

  def evaluate(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]]) = {
    val (watched, results) = evaluateNamed(evaluator, targets)
    // we drop the task name in the inner tuple
    (watched, results.map(_.map(p => (p._1, p._2.map(_._2)))))
  }

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[PathRef], Either[String, Seq[(Any, Option[(TaskName, ujson.Value)])]]) = {
    val evaluated: Evaluator.Results = evaluator.evaluate(targets)
    val watched = evaluated.results
      .iterator
      .collect {
        case (t: Sources, Result.Success(ps: Seq[PathRef])) => ps
        case (t: Source, Result.Success(p: PathRef)) => Seq(p)
      }
      .flatten
      .toSeq

    val errorStr = Evaluator.formatFailing(evaluated)

    evaluated.failing.keyCount match {
      case 0 =>
        val nameAndJson = for (t <- targets.toSeq) yield {
          t match {
            case t: mill.define.Target[_] =>
              val jsonFile = EvaluatorPaths.resolveDestPaths(evaluator.outPath, t).meta
              val metadata = upickle.default.read[Evaluator.Cached](ujson.read(jsonFile.toIO))
              Some(t.toString, metadata.value)

            case _ => None
          }
        }

        watched -> Right(evaluated.values.zip(nameAndJson))
      case n => watched -> Left(s"$n targets failed\n$errorStr")
    }
  }

}
