package mill.eval

import mill.api.{Strict, Val}
import mill.define.{BaseModule, Segments, Task}
import mill.util.{ColorLogger, MultiBiMap}
import os.Path

private[mill] class ProxyEvaluator(eval: => Evaluator) extends Evaluator{
  def baseLogger: ColorLogger = eval.baseLogger
  def rootModule: BaseModule = eval.rootModule
  def effectiveThreadCount: Int = eval.effectiveThreadCount
  def outPath: Path = eval.outPath
  def externalOutPath: Path = eval.externalOutPath
  def pathsResolver: EvaluatorPathsResolver = eval.pathsResolver
  def workerCache: collection.Map[Segments, (Int, Val)] = eval.workerCache
  def withBaseLogger(newBaseLogger: ColorLogger): Evaluator = eval.withBaseLogger(newBaseLogger: ColorLogger)
  def withFailFast(newFailFast: Boolean): Evaluator = eval.withFailFast(newFailFast: Boolean)
  def plan(goals: Strict.Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Strict.Agg[Task[_]]) = eval.plan(goals: Strict.Agg[Task[_]])
  def evalOrThrow(exceptionFactory: Evaluator.Results => Throwable): Evaluator.EvalOrThrow = eval.evalOrThrow(exceptionFactory: Evaluator.Results => Throwable)
}
