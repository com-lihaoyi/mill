package mill.eval

import mill.runner.api._
import mill.api.PathRef

import mill.constants.OutFiles
import mill.define.*
import mill.exec.{Execution, PlanImpl}
import mill.define.internal.Watchable
import OutFiles.*
import mill.resolve.Resolve

/**
 * [[EvaluatorImpl]] is the primary API through which a user interacts with the Mill
 * evaluation process. The various phases of evaluation as methods they can call:
 *
 * 1. [[resolveSegments]]/[[resolveTasks]]
 * 2. [[plan]]
 * 3. [[execute]]/[[execute]],
 *
 * As well as [[evaluate]] which does all of these phases one after another
 */

final class EvaluatorImpl private[mill] (
    private[mill] val allowPositionalCommandArgs: Boolean,
    private[mill] val selectiveExecution: Boolean = false,
    private val execution: Execution
) extends Evaluator {

  private[mill] def workspace = execution.workspace
  private[mill] def baseLogger = execution.baseLogger
  private[mill] def outPath = execution.outPath
  private[mill] def codeSignatures = execution.codeSignatures
  private[mill] def rootModule = execution.rootModule.asInstanceOf[BaseModule]
  private[mill] def workerCache = execution.workerCache
  private[mill] def env = execution.env
  private[mill] def effectiveThreadCount = execution.effectiveThreadCount

  def withBaseLogger(newBaseLogger: Logger): Evaluator = new EvaluatorImpl(
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
  ): mill.api.Result[List[Segments]] = {
    os.checker.withValue(EvaluatorImpl.resolveChecker) {
      Resolve.Segments.resolve(
        rootModule,
        scriptArgs,
        selectMode,
        allowPositionalCommandArgs,
        resolveToModuleTasks
      )
    }
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
  ): mill.api.Result[List[NamedTask[?]]] = {
    os.checker.withValue(EvaluatorImpl.resolveChecker) {
      Evaluator.currentEvaluator0.withValue(this) {
        Resolve.Tasks.resolve(
          rootModule,
          scriptArgs,
          selectMode,
          allowPositionalCommandArgs,
          resolveToModuleTasks
        )
      }
    }
  }

  /**
   * Takes a sequence of [[Task]]s and returns a [[PlanImpl]] containing the
   * transitive upstream tasks necessary to evaluate those provided.
   */
  def plan(tasks: Seq[Task[?]]): Plan = PlanImpl.plan(tasks)

  /**
   * @param targets
   * @param selectiveExecution
   * @return
   */
  def execute[T](
      targets: Seq[Task[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: Logger = baseLogger,
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
        val watched = (evaluated.transitiveResults.iterator ++ selectiveResults)
          .collect {
            case (t: SourcesImpl, ExecResult.Success(Val(ps: Seq[PathRef]))) =>
              ps.map(r => Watchable.Path(r.path.toNIO, r.quick, r.sig))
            case (t: SourceImpl, ExecResult.Success(Val(p: PathRef))) =>
              Seq(Watchable.Path(p.path.toNIO, p.quick, p.sig))
            case (t: InputImpl[_], result) =>

              val ctx = new mill.api.Ctx.Impl(
                args = Vector(),
                dest0 = () => null,
                log = logger,
                env = this.execution.env,
                reporter = reporter,
                testReporter = testReporter,
                workspace = workspace,
                systemExit = _ => ???,
                fork = null,
                jobs = execution.effectiveThreadCount
              )
              val pretty = t.ctx0.fileName + ":" + t.ctx0.lineNum
              Seq(Watchable.Value(
                () => t.evaluate(ctx).hashCode(),
                result.map(_.value).hashCode(),
                pretty
              ))
          }
          .flatten
          .toSeq

        val allInputHashes = evaluated.transitiveResults
          .iterator
          .collect {
            case (t: InputImpl[_], ExecResult.Success(Val(value))) =>
              (t.ctx.segments.render, value.##)
          }
          .toMap

        if (selectiveExecutionEnabled) {
          SelectiveExecution.saveMetadata(
            this,
            SelectiveExecution.Metadata(allInputHashes, codeSignatures)
          )
        }

        val errorStr = EvaluatorImpl.formatFailing(evaluated)
        evaluated.transitiveFailing.size match {
          case 0 =>
            Evaluator.Result(
              watched,
              mill.api.Result.Success(evaluated.values.map(_._1.asInstanceOf[T])),
              selectedTasks,
              evaluated
            )
          case n =>
            Evaluator.Result(
              watched,
              mill.api.Result.Failure(s"$n tasks failed\n$errorStr"),
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
  ): mill.api.Result[Evaluator.Result[Any]] = {
    val resolved = os.checker.withValue(EvaluatorImpl.resolveChecker) {
      Evaluator.currentEvaluator0.withValue(this) {
        Resolve.Tasks.resolve(
          rootModule,
          scriptArgs,
          selectMode,
          allowPositionalCommandArgs
        )
      }
    }

    for (targets <- resolved)
      yield execute(Seq.from(targets), selectiveExecution = selectiveExecution)
  }

  def close(): Unit = execution.close()

}
object EvaluatorImpl {
  def withResolveChecker[T](f: () => T): T = {
    os.checker.withValue(resolveChecker) {
      f()
    }
  }
  val resolveChecker = new os.Checker {
    def onRead(path: os.ReadablePath): Unit = ()

    def onWrite(path: os.Path): Unit = {
      sys.error(s"Writing to $path not allowed during resolution phase")
    }
  }
  private[mill] def formatFailing(evaluated: ExecutionResultsApi): String = {
    (for ((k, fs) <- evaluated.transitiveFailingApi)
      yield {
        val fss = fs match {
          case ExecResult.Failure(t) => t
          case ex: ExecResult.Exception => ex.toString
        }
        s"$k $fss"
      }).mkString("\n")
  }

}
