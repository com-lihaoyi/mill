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

object LauncherRunnerStateTests extends TestSuite {
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
    test("LauncherRunnerState owns evaluators and leases until close") {
      val closed = mutable.Buffer.empty[String]
      val metaEvaluator = new StubEvaluator(() => closed += "meta")
      val finalEvaluator = new StubEvaluator(() => closed += "final")

      val state = LauncherRunnerState.empty
        .withMetaBuildFrame(
          LauncherRunnerState.MetaBuildFrame(
            depth = 1,
            evaluator = metaEvaluator,
            evalWatched = Nil,
            moduleWatched = Nil,
            reusable = None,
            metaBuildReadLease = Some(() => closed += "lease")
          )
        )
        .withFinalFrame(LauncherRunnerState.FinalFrame(0, finalEvaluator, Nil, Nil))
        .withCloseable(() => closed += "manager")

      assert(state.allEvaluators == Seq(finalEvaluator, metaEvaluator))
      assert(closed.isEmpty)

      state.close()

      // Expected order: evaluators (final, then meta-build from shallowest to deepest),
      // then meta-build read leases in the same order, then closeables.
      assert(closed.toSeq == Seq("final", "meta", "lease", "manager"))
    }

    test("metaBuildFrameAt and moduleWatchedAt find the right depth") {
      val overlay0 = LauncherRunnerState.MetaBuildFrame.failed(0, new StubEvaluator(() => ()), Nil, Nil)
      val overlay1 = LauncherRunnerState.MetaBuildFrame.failed(1, new StubEvaluator(() => ()), Nil, Nil)

      val state = LauncherRunnerState.empty
        .withMetaBuildFrame(overlay1)
        .withMetaBuildFrame(overlay0)

      assert(state.metaBuildFrames.map(_.depth) == List(0, 1))
      assert(state.metaBuildFrameAt(0).contains(overlay0))
      assert(state.metaBuildFrameAt(1).contains(overlay1))
      assert(state.metaBuildFrameAt(2).isEmpty)
      assert(state.moduleWatchedAt(0).contains(Nil))
      assert(state.moduleWatchedAt(5).isEmpty)
      assert(state.processedDepths == 2)
    }

    test("SharedRunnerState frameAt and moduleWatchedAt round-trip") {
      val state = SharedRunnerState.empty.withModuleWatched(0, Nil)
      assert(state.moduleWatchedAt(0).contains(Nil))
      assert(state.moduleWatchedAt(1).isEmpty)
      assert(state.frameAt(0).isEmpty)
    }
  }
}
