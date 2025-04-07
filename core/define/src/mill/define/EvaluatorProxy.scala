package mill.define

import mill.api.*
import mill.define.*
import mill.runner.api.*
final class EvaluatorProxy(delegate: => Evaluator) extends Evaluator {
  override def allowPositionalCommandArgs = delegate.allowPositionalCommandArgs
  override def selectiveExecution = delegate.selectiveExecution
  override def workspace = delegate.workspace
  override def baseLogger = delegate.baseLogger
  override def outPath = delegate.outPath
  override def codeSignatures = delegate.codeSignatures
  override def rootModule = delegate.rootModule
  override def workerCache = delegate.workerCache
  override def env = delegate.env
  override def effectiveThreadCount = delegate.effectiveThreadCount

  def withBaseLogger(newBaseLogger: Logger): Evaluator = delegate.withBaseLogger(newBaseLogger)

  def resolveSegments(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      allowPositionalCommandArgs: Boolean,
      resolveToModuleTasks: Boolean
  ): mill.api.Result[List[Segments]] = {
    delegate.resolveSegments(
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
  ): mill.api.Result[List[NamedTask[?]]] = {
    delegate.resolveTasks(scriptArgs, selectMode, allowPositionalCommandArgs, resolveToModuleTasks)
  }
  def plan(tasks: Seq[Task[?]]): Plan = delegate.plan(tasks)

  def execute[T](
      targets: Seq[Task[T]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: Logger = baseLogger,
      serialCommandExec: Boolean = false,
      selectiveExecution: Boolean = false
  ): Evaluator.Result[T] = {
    delegate.execute(
      targets,
      reporter,
      testReporter,
      logger,
      serialCommandExec,
      selectiveExecution
    )
  }

  def evaluate(
      scriptArgs: Seq[String],
      selectMode: SelectMode,
      selectiveExecution: Boolean = false
  ): mill.api.Result[Evaluator.Result[Any]] = {
    delegate.evaluate(scriptArgs, selectMode, selectiveExecution)
  }
  def close = delegate.close()
}
