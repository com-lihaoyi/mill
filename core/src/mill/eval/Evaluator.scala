package mill.eval

import mill.api.{ColorLogger, PathRef, Result, Strict, SystemStreams, Val}
import mill.api.Strict.Agg
import mill.define.*
import mill.exec.{
  Cached,
  ChromeProfileLogger,
  ExecResults,
  ExecutionCore,
  ExecutionPaths,
  EvaluatorPathsResolver,
  Plan,
  ProfileLogger,
  TaskResult
}
import mill.internal.Watchable
import mill.main.client.OutFiles
import mill.main.client.OutFiles.*
import mill.resolve.{Resolve, SelectMode}
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.DynamicVariable

/**
 * Implementation of [[Evaluator]], which serves both as internal logic as well
 * as an odd bag of user-facing helper methods. Internal-only logic is
 * extracted into [[ExecutionCore]]
 */
final case class Evaluator private[mill] (
    home: os.Path,
    workspace: os.Path,
    outPath: os.Path,
    externalOutPath: os.Path,
    override val rootModule: mill.define.BaseModule,
    baseLogger: ColorLogger,
    classLoaderSigHash: Int,
    classLoaderIdentityHash: Int,
    workerCache: mutable.Map[Segments, (Int, Val)] = mutable.Map.empty,
    env: Map[String, String],
    failFast: Boolean,
    threadCount: Option[Int],
    val methodCodeHashSignatures: Map[String, Int],
    val allowPositionalCommandArgs: Boolean,
    val systemExit: Int => Nothing,
    val exclusiveSystemStreams: SystemStreams,
    val selectiveExecution: Boolean = false,
    chromeProfileLogger: ChromeProfileLogger,
    profileLogger: ProfileLogger
) extends ExecutionCore with AutoCloseable {
  import Evaluator._

  private[mill] final def mutableWorkerCache: collection.mutable.Map[Segments, (Int, Val)] =
    workerCache match {
      case mut: collection.mutable.Map[Segments, (Int, Val)] => mut
    }
  val pathsResolver: EvaluatorPathsResolver = EvaluatorPathsResolver.default(outPath)

  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator =
    this.copy(baseLogger = newBaseLogger)

  def withFailFast(newFailFast: Boolean): Evaluator =
    this.copy(failFast = newFailFast)

  def plan(goals: Agg[Task[?]]): Plan = {
    Plan.plan(goals)
  }

  def evalOrThrow(exceptionFactory: ExecResults => Throwable =
    r =>
      new Exception(s"Failure during task evaluation: ${formatFailing(r)}"))
      : Evaluator.EvalOrThrow =
    new EvalOrThrow(this, exceptionFactory)

  def resolveSegments(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): Either[String, List[Segments]] = {
    Resolve.Segments.resolve(
      rootModule,
      scriptArgs,
      selectMode,
      allowPositionalCommandArgs,
      resolveToModuleTasks
    )
  }

  def resolveTasks(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): Either[String, List[NamedTask[?]]] = {
    Resolve.Tasks.resolve(
      rootModule,
      scriptArgs,
      selectMode,
      allowPositionalCommandArgs,
      resolveToModuleTasks
    )
  }

  def close(): Unit = {
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
      if (selectiveExecutionEnabled && os.exists(outPath / OutFiles.millSelectiveExecution)) {
        val changedTasks = SelectiveExecution.computeChangedTasks0(this, targets.toSeq)
        val selectedSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet
        (
          targets.filter(t => t.isExclusiveCommand || selectedSet(t.ctx.segments.render)),
          changedTasks.results
        )
      } else (targets -> Map.empty)

    selectedTargetsOrErr match {
      case (selectedTargets, selectiveResults) =>
        val evaluated: ExecResults = evaluate(selectedTargets, serialCommandExec = true)
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
                  val jsonFile = ExecutionPaths.resolveDestPaths(outPath, t).meta
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

private[mill] object Evaluator {

  class EvalOrThrow(evaluator: Evaluator, exceptionFactory: ExecResults => Throwable) {
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

  type TaskName = String
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new DynamicVariable[mill.eval.Evaluator](null)
  val allBootstrapEvaluators = new DynamicVariable[AllBootstrapEvaluators](null)

  /**
   * Holds all [[Evaluator]]s needed to evaluate the targets of the project and all it's bootstrap projects.
   */
  case class AllBootstrapEvaluators(value: Seq[Evaluator])

  val defaultEnv: Map[String, String] = System.getenv().asScala.toMap

  def formatFailing(evaluated: ExecResults): String = {
    (for ((k, fs) <- evaluated.failing.items())
      yield {
        val fss = fs.map {
          case Result.Failure(t, _) => t
          case Result.Exception(Result.Failure(t, _), _) => t
          case ex: Result.Exception => ex.toString
        }
        s"${k} ${fss.iterator.mkString(", ")}"
      }).mkString("\n")
  }

}
