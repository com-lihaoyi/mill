package mill.eval

import mill.api.{ColorLogger, PathRef, Result, Strict, SystemStreams, Val}
import mill.api.Strict.Agg
import mill.define.*
import mill.exec.{Cached, ChromeProfileLogger, EvalResults, EvaluatorCore, EvaluatorPaths, EvaluatorPathsResolver, Plan, ProfileLogger, TaskResult}
import mill.internal.Watchable
import mill.main.client.OutFiles
import mill.main.client.OutFiles.*
import mill.resolve.{Resolve, SelectMode}

import scala.collection.mutable
import scala.reflect.ClassTag

/**
 * Implementation of [[Evaluator]], which serves both as internal logic as well
 * as an odd bag of user-facing helper methods. Internal-only logic is
 * extracted into [[EvaluatorCore]]
 */
private[mill] case class EvaluatorImpl(
    home: os.Path,
    workspace: os.Path,
    outPath: os.Path,
    externalOutPath: os.Path,
    override val rootModule: mill.define.BaseModule,
    baseLogger: ColorLogger,
    classLoaderSigHash: Int,
    classLoaderIdentityHash: Int,
    workerCache: mutable.Map[Segments, (Int, Val)],
    env: Map[String, String],
    failFast: Boolean,
    threadCount: Option[Int],
    scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])],
    override val methodCodeHashSignatures: Map[String, Int],
    override val disableCallgraph: Boolean,
    override val allowPositionalCommandArgs: Boolean,
    val systemExit: Int => Nothing,
    val exclusiveSystemStreams: SystemStreams,
    protected[mill] val chromeProfileLogger: ChromeProfileLogger,
    protected[mill] val profileLogger: ProfileLogger,
    override val selectiveExecution: Boolean = false
) extends Evaluator with EvaluatorCore {
  import EvaluatorImpl._

  val pathsResolver: EvaluatorPathsResolver = EvaluatorPathsResolver.default(outPath)

  override def withBaseLogger(newBaseLogger: ColorLogger): Evaluator =
    this.copy(baseLogger = newBaseLogger)

  override def withFailFast(newFailFast: Boolean): Evaluator =
    this.copy(failFast = newFailFast)

  override def plan(goals: Agg[Task[?]]): Plan = {
    Plan.plan(goals)
  }

  override def evalOrThrow(exceptionFactory: EvalResults => Throwable)
      : Evaluator.EvalOrThrow =
    new EvalOrThrow(this, exceptionFactory)

  override def close(): Unit = {
    chromeProfileLogger.close()
    profileLogger.close()
  }

  def evaluateTasksNamed(
                          scriptArgs: Seq[String],
                          selectMode: SelectMode,
                          selectiveExecution: Boolean = false
                        ): Either[
    String,
    (Seq[Watchable], Either[String, Seq[(Any, Option[(Evaluator.TaskName, ujson.Value)])]])
  ] = {
    val resolved = mill.eval.Evaluator.currentEvaluator.withValue(this) {
      Resolve.Tasks.resolve(
        rootModule,
        scriptArgs,
        selectMode,
        allowPositionalCommandArgs
      )
    }
    for (targets <- resolved)
      yield evaluateNamed(Agg.from(targets), selectiveExecution)
  }

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluateNamed(
                     targets: Agg[NamedTask[Any]],
                     selectiveExecution: Boolean = false
                   ): (Seq[Watchable], Either[String, Seq[(Any, Option[(Evaluator.TaskName, ujson.Value)])]]) = {

    val selectiveExecutionEnabled = selectiveExecution && !targets.exists(_.isExclusiveCommand)

    val selectedTargetsOrErr =
      if (
        selectiveExecutionEnabled && os.exists(outPath / OutFiles.millSelectiveExecution)
      ) {
        val changedTasks = SelectiveExecution.computeChangedTasks0(this, targets.toSeq)
        val selectedSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
        (
          targets.filter(t => t.isExclusiveCommand || selectedSet(t.ctx.segments.render)),
          changedTasks.results
        )
      } else (targets -> Map.empty)

    selectedTargetsOrErr match {
      case (selectedTargets, selectiveResults) =>
        val evaluated: EvalResults = evaluate(selectedTargets, serialCommandExec = true)
        @scala.annotation.nowarn("msg=cannot be checked at runtime")
        val watched = (evaluated.results.iterator ++ selectiveResults)
          .collect {
            case (t: SourcesImpl, TaskResult(Result.Success(Val(ps: Seq[PathRef])), _)) =>
              ps.map(Watchable.Path(_))
            case (t: SourceImpl, TaskResult(Result.Success(Val(p: PathRef)), _)) =>
              Seq(Watchable.Path(p))
            case (t: InputImpl[_], TaskResult(result, recalc)) =>
              val pretty = t.ctx0.fileName + ":" + t.ctx0.lineNum
              Seq(Watchable.Value(() => recalc().hashCode(), result.hashCode(), pretty))
          }
          .flatten
          .toSeq

        val allInputHashes = evaluated.results
          .iterator
          .collect {
            case (t: InputImpl[_], TaskResult(Result.Success(Val(value)), _)) =>
              (t.ctx.segments.render, value.##)
          }
          .toMap

        if (selectiveExecutionEnabled) {
          SelectiveExecution.saveMetadata(
            this,
            SelectiveExecution.Metadata(allInputHashes, methodCodeHashSignatures)
          )
        }

        val errorStr = Evaluator.formatFailing(evaluated)
        evaluated.failing.keyCount match {
          case 0 =>
            val nameAndJson = for (t <- selectedTargets.toSeq) yield {
              t match {
                case t: mill.define.NamedTask[_] =>
                  val jsonFile = EvaluatorPaths.resolveDestPaths(outPath, t).meta
                  val metadata = upickle.default.read[Cached](ujson.read(jsonFile.toIO))
                  Some((t.toString, metadata.value))
              }
            }

            watched -> Right(evaluated.values.zip(nameAndJson))
          case n => watched -> Left(s"$n tasks failed\n$errorStr")
        }
    }
  }
}

private[mill] object EvaluatorImpl {
  def make(
      home: os.Path,
      workspace: os.Path,
      outPath: os.Path,
      externalOutPath: os.Path,
      rootModule: mill.define.BaseModule,
      baseLogger: ColorLogger,
      classLoaderSigHash: Int,
      classLoaderIdentityHash: Int,
      workerCache: mutable.Map[Segments, (Int, Val)] = mutable.Map.empty,
      env: Map[String, String] = Evaluator.defaultEnv,
      failFast: Boolean = true,
      threadCount: Option[Int] = Some(1),
      scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])] = Map.empty,
      methodCodeHashSignatures: Map[String, Int],
      disableCallgraph: Boolean,
      allowPositionalCommandArgs: Boolean,
      systemExit: Int => Nothing,
      exclusiveSystemStreams: SystemStreams,
      selectiveExecution: Boolean
  ) = new EvaluatorImpl(
    home,
    workspace,
    outPath,
    externalOutPath,
    rootModule,
    baseLogger,
    classLoaderSigHash,
    classLoaderIdentityHash,
    workerCache,
    env,
    failFast,
    threadCount,
    scriptImportGraph,
    methodCodeHashSignatures,
    disableCallgraph,
    allowPositionalCommandArgs,
    systemExit,
    exclusiveSystemStreams,
    chromeProfileLogger = new ChromeProfileLogger(outPath / millChromeProfile),
    profileLogger = new ProfileLogger(outPath / millProfile),
    selectiveExecution = selectiveExecution
  )

  class EvalOrThrow(evaluator: Evaluator, exceptionFactory: EvalResults => Throwable)
      extends Evaluator.EvalOrThrow {
    def apply[T: ClassTag](task: Task[T]): T =
      evaluator.evaluate(Agg(task)) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r =>
          // Input is a single-item Agg, so we also expect a single-item result
          val Seq(Val(e: T)) = r.values: @unchecked
          e
      }

    def apply[T: ClassTag](tasks: Seq[Task[T]]): Seq[T] =
      evaluator.evaluate(tasks) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r => r.values.map(_.value).asInstanceOf[Seq[T]]
      }
  }
}
