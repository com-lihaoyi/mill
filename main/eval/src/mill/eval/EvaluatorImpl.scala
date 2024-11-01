package mill.eval

import mill.api.{CompileProblemReporter, Strict, SystemStreams, TestReporter, Val}
import mill.api.Strict.Agg
import mill.define._
import mill.util._
import mill.main.client.OutFiles._

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
    methodCodeHashSignatures: Map[String, Int],
    override val disableCallgraph: Boolean,
    override val allowPositionalCommandArgs: Boolean,
    val systemExit: Int => Nothing,
    val exclusiveSystemStreams: SystemStreams,
    protected[eval] val chromeProfileLogger: ChromeProfileLogger,
    protected[eval] val profileLogger: ProfileLogger
) extends Evaluator with EvaluatorCore {
  import EvaluatorImpl._


  val pathsResolver: EvaluatorPathsResolver = EvaluatorPathsResolver.default(outPath)

  override def withBaseLogger(newBaseLogger: ColorLogger): Evaluator =
    this.copy(baseLogger = newBaseLogger)

  override def withFailFast(newFailFast: Boolean): Evaluator =
    this.copy(failFast = newFailFast)

  override def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Agg[Task[_]]) = {
    Plan.plan(goals)
  }



  override def evaluate(
      goals: Strict.Agg[Task[_]],
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger,
      serialCommandExec: Boolean
  ): Evaluator.Results = {
    // TODO: cleanup once we break bin-compat in Mill 0.13
    // disambiguate override hierarchy
    logger.withPromptUnpaused {
      super.evaluate(goals, reporter, testReporter, logger, serialCommandExec)
    }
  }

  override def evalOrThrow(exceptionFactory: Evaluator.Results => Throwable)
      : Evaluator.EvalOrThrow =
    new EvalOrThrow(this, exceptionFactory)

  override def close() = {
    chromeProfileLogger.close()
    profileLogger.close()
  }
}

private[mill] object EvaluatorImpl {
  def apply(
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
              exclusiveSystemStreams: SystemStreams) = new EvaluatorImpl(
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
  )

  class EvalOrThrow(evaluator: Evaluator, exceptionFactory: Evaluator.Results => Throwable)
      extends Evaluator.EvalOrThrow {
    def apply[T: ClassTag](task: Task[T]): T =
      evaluator.evaluate(Agg(task)) match {
        case r if r.failing.items().nonEmpty =>
          throw exceptionFactory(r)
        case r =>
          // Input is a single-item Agg, so we also expect a single-item result
          val Seq(Val(e: T)) = r.values
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
