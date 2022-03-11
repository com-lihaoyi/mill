package mill.eval

import utest._
import mill.T
import mill.define.Worker
import mill.util.TestEvaluator
class TaskTests(threadCount: Option[Int]) extends TestSuite {
  val tests = Tests {
    object build extends mill.util.TestUtil.BaseModule {
      var count = 0
      var changeOnceCount = 0
      var workerCloseCount = 0
      // Explicitly instantiate `Function1` objects to make sure we get
      // different instances each time
      def staticWorker: Worker[Int => Int] = T.worker {
        new Function1[Int, Int] {
          def apply(v1: Int) = v1 + 1
        }
      }
      def changeOnceWorker: Worker[Int => Int] = T.worker {
        new Function1[Int, Int] {
          def apply(v1: Int): Int = changeOnceInput() + v1
        }
      }
      def noisyWorker: Worker[Int => Int] = T.worker {
        new Function1[Int, Int] {
          def apply(v1: Int) = input() + v1
        }
      }
      def noisyClosableWorker: Worker[(Int => Int) with AutoCloseable] = T.worker {
        new Function1[Int, Int] with AutoCloseable {
          override def apply(v1: Int) = input() + v1
          override def close(): Unit = workerCloseCount += 1
        }
      }
      def changeOnceInput = T.input {
        val ret = changeOnceCount
        if (changeOnceCount != 1) changeOnceCount = 1
        ret
      }
      def input = T.input {
        count += 1
        count
      }
      def task = T.task {
        count += 1
        count
      }
      def taskInput = T { input() }
      def taskNoInput = T { task() }

      def persistent = T.persistent {
        input() // force re-computation
        os.makeDir.all(T.dest)
        os.write.append(T.dest / "count", "hello\n")
        os.read.lines(T.dest / "count").length
      }
      def nonPersistent = T {
        input() // force re-computation
        os.makeDir.all(T.dest)
        os.write.append(T.dest / "count", "hello\n")
        os.read.lines(T.dest / "count").length
      }

      def staticWorkerDownstream = T {
        val w = staticWorker()
        w.apply(1)
      }

      def reevalTrigger = T.input {
        new Object().hashCode()
      }
      def staticWorkerDownstreamReeval = T {
        val w = staticWorker()
        reevalTrigger()
        w.apply(1)
      }

      def noisyWorkerDownstream = T {
        val w = noisyWorker()
        w.apply(1)
      }
      def noisyClosableWorkerDownstream = T {
        val w = noisyClosableWorker()
        w.apply(1)
      }
      def changeOnceWorkerDownstream = T {
        val w = changeOnceWorker()
        w.apply(1)
      }
    }

    "inputs" - {
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a Target run once then are cached
      val check = new TestEvaluator(build, threads = threadCount)

      val Right((1, 1)) = check.apply(build.taskInput)
      val Right((2, 1)) = check.apply(build.taskInput)
      val Right((3, 1)) = check.apply(build.taskInput)

      val Right((4, 1)) = check.apply(build.taskNoInput)
      val Right((4, 0)) = check.apply(build.taskNoInput)
      val Right((4, 0)) = check.apply(build.taskNoInput)
    }

    "persistent" - {
      // Persistent tasks keep the working dir around between runs
      val check = new TestEvaluator(build, threads = threadCount)
      val Right((1, 1)) = check.apply(build.persistent)
      val Right((2, 1)) = check.apply(build.persistent)
      val Right((3, 1)) = check.apply(build.persistent)

      val Right((1, 1)) = check.apply(build.nonPersistent)
      val Right((1, 1)) = check.apply(build.nonPersistent)
      val Right((1, 1)) = check.apply(build.nonPersistent)
    }

    "worker" - {
      "static" - {
        val check = new TestEvaluator(build, threads = threadCount)
        assert(
          check.apply(build.staticWorkerDownstream) == Right((2, 1)),
          check.evaluator.workerCache.size == 1
        )
        val firstCached = check.evaluator.workerCache.head

        assert(
          check.apply(build.staticWorkerDownstream) == Right((2, 0)),
          check.evaluator.workerCache.head == firstCached,
          check.apply(build.staticWorkerDownstream) == Right((2, 0)),
          check.evaluator.workerCache.head == firstCached
        )
      }
      "staticButReevaluated" - {
        val check = new TestEvaluator(build, threads = threadCount)

        assert(
          check.apply(build.staticWorkerDownstreamReeval) == Right((2, 1)),
          check.evaluator.workerCache.size == 1
        )
        val firstCached = check.evaluator.workerCache.head

        assert(
          check.apply(build.staticWorkerDownstreamReeval) == Right((2, 1)),
          check.evaluator.workerCache.head == firstCached,
          check.apply(build.staticWorkerDownstreamReeval) == Right((2, 1)),
          check.evaluator.workerCache.head == firstCached
        )
      }
      "changedOnce" - {
        val check = new TestEvaluator(build, threads = threadCount)
        assert(
          check.apply(build.changeOnceWorkerDownstream) == Right((1, 1)),
          // changed
          check.apply(build.changeOnceWorkerDownstream) == Right((2, 1)),
          check.apply(build.changeOnceWorkerDownstream) == Right((2, 0))
        )
      }
      "alwaysChanged" - {
        val check = new TestEvaluator(build, threads = threadCount)

        assert(
          check.apply(build.noisyWorkerDownstream) == Right((2, 1)),
          check.evaluator.workerCache.size == 1
        )
        val firstCached = check.evaluator.workerCache.head

        assert(
          check.apply(build.noisyWorkerDownstream) == Right((3, 1)),
          check.evaluator.workerCache.size == 1,
          check.evaluator.workerCache.head != firstCached
        )
        val secondCached = check.evaluator.workerCache.head

        assert(
          check.apply(build.noisyWorkerDownstream) == Right((4, 1)),
          check.evaluator.workerCache.size == 1,
          check.evaluator.workerCache.head != secondCached
        )
      }
      "closableWorker" - {
        val check = new TestEvaluator(build, threads = threadCount)

        assert(
          check.apply(build.noisyClosableWorkerDownstream) == Right((2, 1)),
          check.evaluator.workerCache.size == 1,
          build.workerCloseCount == 0
        )
        val firstCached = check.evaluator.workerCache.head

        assert(
          check.apply(build.noisyClosableWorkerDownstream) == Right((3, 1)),
          check.evaluator.workerCache.size == 1,
          build.workerCloseCount == 1,
          check.evaluator.workerCache.head != firstCached
        )
        val secondCached = check.evaluator.workerCache.head

        assert(
          check.apply(build.noisyClosableWorkerDownstream) == Right((4, 1)),
          check.evaluator.workerCache.size == 1,
          check.evaluator.workerCache.head != secondCached
        )
      }
    }
  }
}

object SeqTaskTests extends TaskTests(Some(1))
object ParTaskTests extends TaskTests(Some(16))
