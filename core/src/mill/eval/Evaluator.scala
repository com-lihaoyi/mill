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
import mill.exec.{Execution, Plan}
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
 * 3. [[execute]]/[[execute]],
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
  private[mill] def env = execution.env

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

  /**
   * @param targets
   * @param selectiveExecution
   * @return
   */
  def execute[T](
      targets: Seq[Task[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): Evaluator.Result[T] = {

    val selectiveExecutionEnabled = selectiveExecution && !targets.exists(_.isExclusiveCommand)

    val selectedTasksOrErr =
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

    selectedTasksOrErr match {
      case (selectedTasks, selectiveResults) =>
        val evaluated: ExecutionResults =
          execution.executeTasks(
            selectedTasks,
            reporter,
            testReporter,
            logger,
            serialCommandExec
          )
        @scala.annotation.nowarn("msg=cannot be checked at runtime")
        val watched = (evaluated.results.iterator ++ selectiveResults)
          .collect {
            case (t: SourcesImpl, ExecResult.Success(Val(ps: Seq[PathRef]))) =>
              ps.map(Watchable.Path(_))
            case (t: SourceImpl, ExecResult.Success(Val(p: PathRef))) =>
              Seq(Watchable.Path(p))
            case (t: InputImpl[_], result) =>

              val ctx = new mill.api.Ctx(
                args = Vector(),
                dest0 = () => null,
                log = logger,
                home = os.home,
                env = this.execution.env,
                reporter = reporter,
                testReporter = testReporter,
                workspace = workspace,
                systemExit = null,
                fork = null
              )
              val pretty = t.ctx0.fileName + ":" + t.ctx0.lineNum
              Seq(Watchable.Value(() => t.evaluate(ctx).hashCode(), result.hashCode(), pretty))
          }
          .flatten
          .toSeq

        val allInputHashes = evaluated.results
          .iterator
          .collect {
            case (t: InputImpl[_], ExecResult.Success(Val(value))) =>
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
            Evaluator.Result(
              watched,
              Result.Success(evaluated.values.map(_._1.asInstanceOf[T])),
              selectedTasks,
              evaluated
            )
          case n =>
            Evaluator.Result(
              watched,
              Result.Failure(s"$n tasks failed\n$errorStr"),
              selectedTasks,
              evaluated
            )
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
  ): Result[Evaluator.Result[Any]] = {
    val resolved = mill.eval.Evaluator.currentEvaluator.withValue(this) {
      Resolve.Tasks.resolve(
        rootModule,
        scriptArgs,
        selectMode,
        allowPositionalCommandArgs
      )
    }
    for (targets <- resolved)
      yield execute(Seq.from(targets), selectiveExecution = selectiveExecution)
  }

  def close(): Unit = execution.close()

}

object Evaluator {

  /**
   * @param watchable the list of [[Watchable]]s that were generated during this evaluation,
   *                  useful if you want to know what to watch in case you need to re-run it.
   * @param values A sequence of values returned by evaluation.
   * @param selectedTasks The tasks that actually were selected to be run during this evaluation
   * @param executionResults Detailed information on the results of executing each task
   */
  case class Result[T](
      watchable: Seq[Watchable],
      values: mill.api.Result[Seq[T]],
      selectedTasks: Seq[Task[?]],
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
