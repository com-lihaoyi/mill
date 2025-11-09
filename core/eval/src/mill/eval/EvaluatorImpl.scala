package mill.eval

import mill.api.daemon.internal.{CompileProblemReporter, ExecutionResultsApi, TestReporter}
import mill.constants.OutFiles
import mill.constants.OutFiles.*
import mill.api.{PathRef, *}
import mill.api.internal.{ResolveChecker, Resolved, RootModule0}
import mill.api.daemon.Watchable
import mill.exec.{Execution, PlanImpl}
import mill.internal.PrefixLogger
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
    private val execution: Execution,
    scriptModuleResolver: (String, String => Option[Module]) => Seq[Result[ExternalModule]]
) extends Evaluator {

  private[mill] def workspace = execution.workspace
  private[mill] def baseLogger = execution.baseLogger
  private[mill] def outPath = execution.outPath
  private[mill] def codeSignatures = execution.codeSignatures
  private[mill] def rootModule = execution.rootModule.asInstanceOf[RootModule0]
  private[mill] def workerCache = execution.workerCache
  private[mill] def env = execution.env
  private[mill] def effectiveThreadCount = execution.effectiveThreadCount
  override private[mill] def offline: Boolean = execution.offline

  def withBaseLogger(newBaseLogger: Logger): Evaluator = new EvaluatorImpl(
    allowPositionalCommandArgs,
    selectiveExecution,
    execution.withBaseLogger(newBaseLogger),
    scriptModuleResolver
  )

  override private[mill] def resolveScriptModuleDep(s: String): Option[mill.Module] = {
    resolveModulesOrTasks(Seq(s), SelectMode.Multi)
      .toOption
      .toSeq
      .flatten
      .collectFirst { case Left(m) => m }
  }

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
    os.checker.withValue(ResolveChecker(workspace)) {
      Resolve.Segments.resolve(
        rootModule = rootModule,
        scriptArgs = scriptArgs,
        selectMode = selectMode,
        allowPositionalCommandArgs = allowPositionalCommandArgs,
        resolveToModuleTasks = resolveToModuleTasks,
        scriptModuleResolver = scriptModuleResolver(_, resolveScriptModuleDep)
      )
    }
  }
  override def resolveRaw(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): mill.api.Result[List[Resolved]] = {
    os.checker.withValue(ResolveChecker(workspace)) {
      Resolve.Raw.resolve(
        rootModule = rootModule,
        scriptArgs = scriptArgs,
        selectMode = selectMode,
        allowPositionalCommandArgs = allowPositionalCommandArgs,
        resolveToModuleTasks = resolveToModuleTasks,
        scriptModuleResolver = scriptModuleResolver(_, resolveScriptModuleDep)
      )
    }
  }

  /**
   * Takes query selector tokens and resolves them to a list of [[Task.Named]]s
   * representing concrete tasks or modules that match that selector
   */
  def resolveTasks(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): mill.api.Result[List[Task.Named[?]]] = {
    os.checker.withValue(ResolveChecker(workspace)) {
      Evaluator.withCurrentEvaluator(this) {
        Resolve.Tasks.resolve(
          rootModule = rootModule,
          scriptArgs = scriptArgs,
          selectMode = selectMode,
          allowPositionalCommandArgs = allowPositionalCommandArgs,
          resolveToModuleTasks = resolveToModuleTasks,
          scriptModuleResolver = scriptModuleResolver(_, resolveScriptModuleDep)
        )
      }
    }
  }
  def resolveModulesOrTasks(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean = false,
      resolveToModuleTasks: Boolean = false
  ): mill.api.Result[List[Either[Module, Task.Named[?]]]] = {
    os.checker.withValue(ResolveChecker(workspace)) {
      Evaluator.withCurrentEvaluator(this) {
        Resolve.Inspect.resolve(
          rootModule = rootModule,
          scriptArgs = scriptArgs,
          selectMode = selectMode,
          allowPositionalCommandArgs = allowPositionalCommandArgs,
          resolveToModuleTasks = resolveToModuleTasks,
          scriptModuleResolver = scriptModuleResolver(_, resolveScriptModuleDep)
        )
      }
    }
  }

  /**
   * Takes a sequence of [[Task]]s and returns a [[PlanImpl]] containing the
   * transitive upstream tasks necessary to evaluate those provided.
   */
  def plan(tasks: Seq[Task[?]]): Plan = PlanImpl.plan(tasks)

  def transitiveTasks(sourceTasks: Seq[Task[?]]) = {
    PlanImpl.transitiveTasks(sourceTasks)
  }

  def topoSorted(transitiveTasks: IndexedSeq[Task[?]]) = {
    PlanImpl.topoSorted(transitiveTasks)
  }

  def groupAroundImportantTasks[T](topoSortedTasks: TopoSorted)(important: PartialFunction[
    Task[?],
    T
  ]) = {
    PlanImpl.groupAroundImportantTasks(topoSortedTasks)(important)
  }

  def execute[T](
      tasks: Seq[Task[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      logger: Logger = baseLogger,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): Evaluator.Result[T] = {
    val selectiveExecutionEnabled = selectiveExecution && !tasks.exists(_.isExclusiveCommand)

    val (selectedTasks, selectiveResults, maybeNewMetadata) =
      if (!selectiveExecutionEnabled) (tasks, Map.empty, None)
      else {
        val (named, unnamed) =
          tasks.partitionMap { case n: Task.Named[?] => Left(n); case t => Right(t) }
        val newComputedMetadata = SelectiveExecutionImpl.Metadata.compute(this, named)

        val selectiveExecutionStoredData = for {
          _ <- Option.when(os.exists(outPath / OutFiles.millSelectiveExecution))(())
          changedTasks <- this.selective.computeChangedTasks0(named, newComputedMetadata)
        } yield changedTasks

        selectiveExecutionStoredData match {
          case None =>
            // Ran when previous selective execution metadata is not available, which happens the first time you run
            // selective execution.
            (tasks, Map.empty, Some(newComputedMetadata.metadata))
          case Some(changedTasks) =>
            val selectedSet = changedTasks.downstreamTasks.map(_.ctx.segments.render).toSet

            (
              unnamed ++ named.filter(t =>
                t.isExclusiveCommand || selectedSet(t.ctx.segments.render)
              ),
              newComputedMetadata.results,
              Some(newComputedMetadata.metadata)
            )
        }
      }

    val evaluated: ExecutionResults = execution.executeTasks(
      goals = selectedTasks,
      reporter = reporter,
      testReporter = testReporter,
      logger = logger,
      serialCommandExec = serialCommandExec
    )

    val scriptHeaderWatches =
      tasks
        .collect { case n: mill.api.Task.Named[_] => n.ctx.enclosingModule.moduleBuildOverridePaths }
        .flatten
        .map(PathRef(_))
        .map(p => Watchable.Path(p.path.toNIO, p.quick, p.sig))

    @scala.annotation.nowarn("msg=cannot be checked at runtime")
    val evalWatches = (evaluated.transitiveResults.iterator ++ selectiveResults)
      .collect {
        case (_: Task.Sources, ExecResult.Success(Val(ps: Seq[PathRef]))) =>
          ps.map(r => Watchable.Path(r.path.toNIO, r.quick, r.sig))
        case (_: Task.Source, ExecResult.Success(Val(p: PathRef))) =>
          Seq(Watchable.Path(p.path.toNIO, p.quick, p.sig))
        case (t: Task.Input[_], result) =>

          val ctx = new mill.api.TaskCtx.Impl(
            args = Vector(),
            dest0 = () => null,
            log = logger,
            env = this.execution.env,
            reporter = reporter,
            testReporter = testReporter,
            workspace = workspace,
            _systemExitWithReason = (reason, exitCode) =>
              throw Exception(s"systemExit called: reason=$reason, exitCode=$exitCode"),
            fork = null,
            jobs = execution.effectiveThreadCount,
            offline = offline
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

    val watched = evalWatches ++ scriptHeaderWatches

    maybeNewMetadata.foreach { newMetadata =>
      val allInputHashes = newMetadata.inputHashes
      this.selective.saveMetadata(SelectiveExecution.Metadata(allInputHashes, codeSignatures))
    }

    val errorStr = ExecutionResultsApi.formatFailing(evaluated)
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

  /**
   * Evaluates the given query selector, performing [[resolveTasks]] and [[execute]]
   * internally, and returning the [[Evaluator.Result]] containing the output
   */
  def evaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      reporter: Int => Option[CompileProblemReporter] = _ => None,
      selectiveExecution: Boolean = false
  ): mill.api.Result[Evaluator.Result[Any]] = {
    val promptLineLogger = new PrefixLogger(
      logger0 = baseLogger,
      key0 = Seq("resolve"),
      message = "resolve " + scriptArgs.mkString(" ")
    )

    val resolved = promptLineLogger.withPromptLine {
      os.checker.withValue(ResolveChecker(workspace)) {
        Evaluator.withCurrentEvaluator(this) {
          Resolve.Tasks.resolve(
            rootModule = rootModule,
            scriptArgs = scriptArgs,
            selectMode = selectMode,
            allowPositionalCommandArgs = allowPositionalCommandArgs,
            scriptModuleResolver = scriptModuleResolver(_, resolveScriptModuleDep)
          )
        }
      }
    }
    for (tasks <- resolved)
      yield execute(Seq.from(tasks), reporter = reporter, selectiveExecution = selectiveExecution)
  }

  def close(): Unit = execution.close()

  val selective = new mill.eval.SelectiveExecutionImpl(this)
}
