package mill.daemon

import mill.api.daemon.internal.{
  BaseModuleApi,
  CompileProblemReporter,
  EvaluatorApi,
  TaskApi,
  TestReporter
}
import mill.api.{Logger, Result, Val}
import utest.*

import scala.collection.mutable

object RunnerStateTests extends TestSuite {
  private class StubEvaluator(onClose: () => Unit) extends EvaluatorApi {
    override def close(): Unit = onClose()

    override def evaluate(
        scriptArgs: Seq[String],
        selectMode: mill.api.SelectMode,
        reporter: Int => Option[CompileProblemReporter],
        selectiveExecution: Boolean
    ): Result[EvaluatorApi.Result[Any]] = sys.error("unused")

    override private[mill] def executeApi[T](
        tasks: Seq[TaskApi[T]],
        reporter: Int => Option[CompileProblemReporter],
        testReporter: TestReporter,
        logger: Logger,
        serialCommandExec: Boolean,
        selectiveExecution: Boolean
    ): EvaluatorApi.Result[T] = sys.error("unused")

    override private[mill] def executeApi[T](tasks: Seq[TaskApi[T]]): EvaluatorApi.Result[T] =
      sys.error("unused")

    override private[mill] def workerCache: mutable.Map[String, (Int, Val, TaskApi[?])] =
      mutable.Map.empty

    override private[mill] def baseLogger: Logger = null
    override private[mill] def rootModule: BaseModuleApi = null
    override private[mill] def outPathJava: java.nio.file.Path = java.nio.file.Path.of(".")
  }

  def tests: Tests = Tests {
    test("RunnerState owns evaluators until close") {
      val closed = mutable.Buffer.empty[String]
      val metaEvaluator = new StubEvaluator(() => closed += "meta")
      val finalEvaluator = new StubEvaluator(() => closed += "final")

      val state = RunnerState.empty
        .withMetaBuildFrame(0, RunnerState.MetaBuildFrame.failed(metaEvaluator, Nil, Nil))
        .withFinalFrame(1, RunnerState.FinalFrame(finalEvaluator, Nil, Nil))
        .withCloseable(new AutoCloseable {
          override def close(): Unit = closed += "manager"
        })

      assert(state.allEvaluators == Seq(finalEvaluator, metaEvaluator))
      assert(closed.isEmpty)

      state.close()

      assert(closed.toSeq == Seq("final", "meta", "manager"))
    }
  }
}
