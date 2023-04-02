package mill.eval

import utest._
import mill.T
import mill.define.Worker
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath

trait TaskTests extends TestSuite {
  trait Build extends TestUtil.BaseModule {
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

  def withEnv(f: (Build, TestEvaluator) => Unit)(implicit tp: TestPath): Unit

  val tests = Tests {

    "inputs" - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a CachedTarget run once then are cached
      check.apply(build.taskInput) ==> Right((1, 1))
      check.apply(build.taskInput) ==> Right((2, 1))
      check.apply(build.taskInput) ==> Right((3, 1))
    }
    "noInputs" - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a CachedTarget run once then are cached
      check.apply(build.taskNoInput) ==> Right((1, 1))
      check.apply(build.taskNoInput) ==> Right((1, 0))
      check.apply(build.taskNoInput) ==> Right((1, 0))
    }

    "persistent" - withEnv { (build, check) =>
      // Persistent tasks keep the working dir around between runs
      println(build.millSourcePath + "\n")
      check.apply(build.persistent) ==> Right((1, 1))
      check.apply(build.persistent) ==> Right((2, 1))
      check.apply(build.persistent) ==> Right((3, 1))
    }
    "nonPersistent" - withEnv { (build, check) =>
      // non-Persistent tasks keep the working dir around between runs
      check.apply(build.nonPersistent) ==> Right((1, 1))
      check.apply(build.nonPersistent) ==> Right((1, 1))
      check.apply(build.nonPersistent) ==> Right((1, 1))
    }

    "worker" - {
      "static" - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check.apply(build.staticWorkerDownstream) ==> Right((2, 1))
        wc.size ==> 1
        val firstCached = wc.head

        check.apply(build.staticWorkerDownstream) ==> Right((2, 0))
        wc.head ==> firstCached
        check.apply(build.staticWorkerDownstream) ==> Right((2, 0))
        wc.head ==> firstCached
      }
      "staticButReevaluated" - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check.apply(build.staticWorkerDownstreamReeval) ==> Right((2, 1))
        check.evaluator.workerCache.size ==> 1
        val firstCached = wc.head

        check.apply(build.staticWorkerDownstreamReeval) ==> Right((2, 1))
        wc.head ==> firstCached
        check.apply(build.staticWorkerDownstreamReeval) ==> Right((2, 1))
        wc.head ==> firstCached
      }
      "changedOnce" - withEnv { (build, check) =>
        check.apply(build.changeOnceWorkerDownstream) ==> Right((1, 1))
        // changed
        check.apply(build.changeOnceWorkerDownstream) ==> Right((2, 1))
        check.apply(build.changeOnceWorkerDownstream) ==> Right((2, 0))
      }
      "alwaysChanged" - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check.apply(build.noisyWorkerDownstream) ==> Right((2, 1))
        wc.size ==> 1
        val firstCached = wc.head

        check.apply(build.noisyWorkerDownstream) ==> Right((3, 1))
        wc.size ==> 1
        assert(wc.head != firstCached)
        val secondCached = wc.head

        check.apply(build.noisyWorkerDownstream) ==> Right((4, 1))
        wc.size ==> 1
        assert(wc.head != secondCached)
      }
      "closableWorker" - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check.apply(build.noisyClosableWorkerDownstream) ==> Right((2, 1))
        wc.size ==> 1
        build.workerCloseCount ==> 0

        val firstCached = wc.head

        check.apply(build.noisyClosableWorkerDownstream) ==> Right((3, 1))
        wc.size ==> 1
        build.workerCloseCount ==> 1
        assert(wc.head != firstCached)

        val secondCached = wc.head

        check.apply(build.noisyClosableWorkerDownstream) ==> Right((4, 1))
        wc.size ==> 1
        assert(wc.head != secondCached)
      }
    }
  }
}

object SeqTaskTests extends TaskTests {
  def withEnv(f: (Build, TestEvaluator) => Unit)(implicit tp: TestPath) = {
    object build extends Build
    val check = new TestEvaluator(
      build,
      threads = Some(1),
      extraPathEnd = Seq(getClass().getSimpleName())
    )
    f(build, check)
  }
}
object ParTaskTests extends TaskTests {
  def withEnv(f: (Build, TestEvaluator) => Unit)(implicit tp: TestPath) = {
    object build extends Build
    val check = new TestEvaluator(
      build,
      threads = Some(16),
      extraPathEnd = Seq(getClass().getSimpleName())
    )
    f(build, check)
  }
}
