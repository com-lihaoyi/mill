package mill.eval

import mill.api.{ColorLogger, ExecResult, PathRef, Result, Val}
import mill.client.OutFiles
import mill.define.*
import mill.exec.{
  Cached,
  ExecResults,
  Execution,
  ExecutionPaths,
  ExecutionPathsResolver,
  Plan,
  TaskResult
}
import mill.define.internal.Watchable
import OutFiles.*
import mill.resolve.Resolve

import scala.jdk.CollectionConverters.*
import scala.util.DynamicVariable

/**
 * Implementation of [[Evaluator]], which serves both as internal logic as well
 * as an odd bag of user-facing helper methods. Internal-only logic is
 * extracted into [[Execution]]
 */
final case class Evaluator private[mill] (
    private[mill] val allowPositionalCommandArgs: Boolean,
    private[mill] val selectiveExecution: Boolean = false,
    private[mill] val execution: Execution
) extends AutoCloseable {

  private[mill] def workspace = execution.workspace
  private[mill] def baseLogger = execution.baseLogger
  private[mill] def outPath = execution.outPath
  private[mill] def methodCodeHashSignatures = execution.methodCodeHashSignatures
  private[mill] def rootModule = execution.rootModule
  private[mill] def workerCache = execution.workerCache

  val pathsResolver: ExecutionPathsResolver = ExecutionPathsResolver.default(outPath)

  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator = new Evaluator(
    allowPositionalCommandArgs,
    selectiveExecution,
    execution.withBaseLogger(newBaseLogger)
  )

  def plan(goals: Seq[Task[?]]): Plan = Plan.plan(goals)

  def resolveSegments(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): Result[List[Segments]] = {
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
  ): Result[List[NamedTask[?]]] = {
    Resolve.Tasks.resolve(
      rootModule,
      scriptArgs,
      selectMode,
      allowPositionalCommandArgs,
      resolveToModuleTasks
    )
  }

  def close(): Unit = {
    execution.close()
  }

  def resolveEvaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      selectiveExecution: Boolean = false
  ): Result[(Seq[Watchable], Result[Seq[(Any, Option[(Evaluator.TaskName, ujson.Value)])]])] = {
    val resolved = mill.eval.Evaluator.currentEvaluator.withValue(this) {
      Resolve.Tasks.resolve(
        rootModule,
        scriptArgs,
        selectMode,
        allowPositionalCommandArgs
      )
    }
    for (targets <- resolved)
      yield evaluate(Seq.from(targets), selectiveExecution)
  }

  def evaluateValues[T](tasks: Seq[Task[T]]): Seq[T] = {
    val (watches, res0) = evaluate(tasks).get
    val results = res0.get
    results.map(_._1.value.asInstanceOf[T])
  }

  /**
   * @param evaluator
   * @param targets
   * @return (watched-paths, Either[err-msg, Seq[(task-result, Option[(task-name, task-return-as-json)])]])
   */
  def evaluate(
      targets: Seq[Task[Any]],
      selectiveExecution: Boolean = false
  ): (Seq[Watchable], Result[Seq[(Val, Option[(Evaluator.TaskName, ujson.Value)])]]) = {

    val selectiveExecutionEnabled = selectiveExecution && !targets.exists(_.isExclusiveCommand)

    val selectedTargetsOrErr =
      if (selectiveExecutionEnabled && os.exists(outPath / OutFiles.millSelectiveExecution)) {
        val (named, unnamed) =
          targets.partitionMap { case n: NamedTask[?] => Left(n); case t => Right(t) }
        val changedTasks = SelectiveExecution.computeChangedTasks0(this, named)

        val selectedSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet

        (
          unnamed ++ named.filter(t => t.isExclusiveCommand || selectedSet(t.ctx.segments.render)),
          changedTasks.results
        )
      } else (targets -> Map.empty)

    selectedTargetsOrErr match {
      case (selectedTargets, selectiveResults) =>
        val evaluated: ExecResults =
          execution.executeTasks(selectedTargets, serialCommandExec = true)
        @scala.annotation.nowarn("msg=cannot be checked at runtime")
        val watched = (evaluated.results.iterator ++ selectiveResults)
          .collect {
            case (t: SourcesImpl, TaskResult(ExecResult.Success(Val(ps: Seq[PathRef])), _)) =>
              ps.map(Watchable.Path(_))
            case (t: SourceImpl, TaskResult(ExecResult.Success(Val(p: PathRef)), _)) =>
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
            case (t: InputImpl[_], TaskResult(ExecResult.Success(Val(value)), _)) =>
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
        evaluated.failing.size match {
          case 0 =>
            val nameAndJson = for (t <- selectedTargets.toSeq) yield {
              t match {
                case t: mill.define.NamedTask[_] =>
                  val jsonFile = ExecutionPaths.resolveDestPaths(outPath, t).meta
                  val metadata = upickle.default.read[Cached](ujson.read(jsonFile.toIO))
                  Some((t.toString, metadata.value))
                case _ => None
              }
            }
            watched -> Result.Success(evaluated.values.zip(nameAndJson))
          case n => watched -> Result.Failure(s"$n tasks failed\n$errorStr")
        }
    }
  }
}

private[mill] object Evaluator {

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
    (for ((k, fs) <- evaluated.failing)
      yield {
        val fss = fs.map {
          case ExecResult.Failure(t) => t
          case ex: ExecResult.Exception => ex.toString
        }
        s"${k} ${fss.iterator.mkString(", ")}"
      }).mkString("\n")
  }

}
