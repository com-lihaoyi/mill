package mill.eval

import mill.api.{
  ColorLogger,
  CompileProblemReporter,
  DummyTestReporter,
  ExecResult,
  PathRef,
  Result,
  TestReporter,
  Val
}
import mill.constants.OutFiles
import mill.define.*
import mill.exec.{Cached, Execution, Plan}
import mill.define.internal.Watchable
import OutFiles.*
import mill.resolve.Resolve

import scala.jdk.CollectionConverters.*
import scala.util.DynamicVariable

/**
 * [[Evaluator]] is the primary API through which a user interacts with the Mill
 * evaluation process. The various phases of evaluation as methods they can call:
 *
 * 1. [[resolveSegments]]/[[resolveTasks]]
 * 2. [[plan]]
 * 3. [[execute]]/[[execute0]],
 *
 * As well as [[evaluate]] which does all of these phases one after another
 */
final class Evaluator private[mill] (
    private[mill] val allowPositionalCommandArgs: Boolean,
    private[mill] val selectiveExecution: Boolean = false,
    private val execution: Execution
) extends AutoCloseable {

  private[mill] def workspace = execution.workspace
  private[mill] def baseLogger = execution.baseLogger
  private[mill] def outPath = execution.outPath
  private[mill] def methodCodeHashSignatures = execution.methodCodeHashSignatures
  private[mill] def rootModule = execution.rootModule
  private[mill] def workerCache = execution.workerCache

  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator = new Evaluator(
    allowPositionalCommandArgs,
    selectiveExecution,
    execution.withBaseLogger(newBaseLogger)
  )

  /**
   * Takes query selector tokens and resolves them to a list of [[Segments]]
   * representing concrete tasks or modules that match that selector
   */
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

  /**
   * Takes query selector tokens and resolves them to a list of [[NamedTask]]s
   * representing concrete tasks or modules that match that selector
   */
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

  /**
   * Takes a sequence of [[Task]]s and returns a [[Plan]] containing the
   * transitive upstream tasks necessary to evaluate those provided.
   */
  def plan(tasks: Seq[Task[?]]): Plan = Plan.plan(tasks)

  def execute[T](tasks: Seq[Task[T]]): Seq[T] = {
    val res0 = execute0(tasks)
    val results = res0.values.get
    results.map(_._1.value.asInstanceOf[T])
  }

  /**
   * @param targets
   * @param selectiveExecution
   * @return
   */
  def execute0(
      targets: Seq[Task[Any]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): Evaluator.Result = {

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
      } else (targets, Map.empty)

    selectedTargetsOrErr match {
      case (selectedTargets, selectiveResults) =>
        val evaluated: ExecutionResults =
          execution.executeTasks(
            selectedTargets,
            reporter,
            testReporter,
            logger,
            serialCommandExec
          )
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
                  val jsonFile = ExecutionPaths.resolve(outPath, t).meta
                  val metadata = upickle.default.read[Cached](ujson.read(jsonFile.toIO))
                  Some((t.toString, metadata.value))
                case _ => None
              }
            }
            Evaluator.Result(watched, Result.Success(evaluated.values.zip(nameAndJson)), evaluated)
          case n =>
            Evaluator.Result(watched, Result.Failure(s"$n tasks failed\n$errorStr"), evaluated)
        }
    }
  }

  /**
   * Evaluates the given query selector, performing [[resolveTasks]] and [[execute]]
   * internally, and returning the [[Evaluator.Result]] containing the output
   */
  def evaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      selectiveExecution: Boolean = false
  ): Result[Evaluator.Result] = {
    val resolved = mill.eval.Evaluator.currentEvaluator.withValue(this) {
      Resolve.Tasks.resolve(
        rootModule,
        scriptArgs,
        selectMode,
        allowPositionalCommandArgs
      )
    }
    for (targets <- resolved)
      yield execute0(Seq.from(targets), selectiveExecution = selectiveExecution)
  }

  def close(): Unit = execution.close()

}

object Evaluator {

  /**
   * @param watchable
   * @param values A sequence of untyped values returned by evaluation, boxed in [[Val]]s,
   *               along with an optional task name and JSON-serialization for named tasks
   * @param executionResults Detailed information on the results of executing each task
   */
  case class Result(
      watchable: Seq[Watchable],
      values: mill.api.Result[Seq[(Val, Option[(String, ujson.Value)])]],
      executionResults: ExecutionResults
  )

  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  private[mill] val currentEvaluator = new DynamicVariable[mill.eval.Evaluator](null)
  private[mill] val allBootstrapEvaluators = new DynamicVariable[AllBootstrapEvaluators](null)

  /**
   * Holds all [[Evaluator]]s needed to evaluate the targets of the project and all it's bootstrap projects.
   */
  case class AllBootstrapEvaluators(value: Seq[Evaluator])

  private[mill] val defaultEnv: Map[String, String] = System.getenv().asScala.toMap

  private[mill] def formatFailing(evaluated: ExecutionResults): String = {
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
