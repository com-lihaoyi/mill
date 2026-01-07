package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildClient, BuildTargetIdentifier}
import mill.api.daemon.Logger
import mill.api.daemon.internal.{
  CompileProblemReporter,
  EvaluatorApi,
  ExecutionResultsApi,
  TaskApi,
  TestReporter
}
import mill.api.daemon.internal.bsp.{BspModuleApi, BspServerResult}

import java.util.concurrent.CompletableFuture

trait EndpointsApi {

  protected def topLevelProjectRoot: os.Path
  protected def bspVersion: String
  protected def serverVersion: String
  protected def serverName: String
  protected def canReload: Boolean
  protected def onShutdown: () => Unit
  protected def baseLogger: Logger

  protected def client: BuildClient
  protected def sessionInfo: MillBspEndpoints.SessionInfo
  protected def sessionInfo_=(info: MillBspEndpoints.SessionInfo): Unit
  protected[worker] def sessionResult: scala.concurrent.Promise[BspServerResult]
  protected[worker] def sessionResult_=(p: scala.concurrent.Promise[BspServerResult]): Unit

  protected def handlerRaw[V](block: Logger => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V]

  protected def handlerEvaluators[V](
      checkInitialized: Boolean = true
  )(block: (BspEvaluators, Logger) => V)(using
      name: sourcecode.Name,
      enclosing: sourcecode.Enclosing
  ): CompletableFuture[V]

  protected def handlerTasks[T, V, W](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String,
      originId: String
  )(block: (EvaluatorApi, BspEvaluators, BuildTargetIdentifier, BspModuleApi, W) => T)(
      agg: java.util.List[T] => V
  )(using name: sourcecode.Name, enclosing: sourcecode.Enclosing): CompletableFuture[V]

  protected def handlerTasksEvaluators[T, V, W](
      targetIds: BspEvaluators => collection.Seq[BuildTargetIdentifier],
      tasks: PartialFunction[BspModuleApi, TaskApi[W]],
      requestDescription: String,
      originId: String
  )(block: (EvaluatorApi, BspEvaluators, BuildTargetIdentifier, BspModuleApi, W) => T)(
      agg: (java.util.List[T], BspEvaluators) => V
  )(using name: sourcecode.Name, enclosing: sourcecode.Enclosing): CompletableFuture[V]

  protected def createLogger()(using enclosing: sourcecode.Enclosing): Logger

  protected def evaluate(
      evaluator: EvaluatorApi,
      requestDescription: String,
      goals: Seq[TaskApi[?]],
      logger: Logger,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter = TestReporter.DummyTestReporter,
      errorOpt: EvaluatorApi.Result[Any] => Option[String] = evaluatorErrorOpt
  ): ExecutionResultsApi

  protected def evaluatorErrorOpt(result: EvaluatorApi.Result[Any]): Option[String]

}
