package mill.eval

import utest._
import mill.{T, Task}
import mill.define.{Discover, Module, Worker}
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath

trait TaskTests extends TestSuite {
  trait SuperBuild extends TestUtil.BaseModule {

    var superBuildInputCount = 0

    def superBuildInputOverrideWithConstant = Task.input {
      superBuildInputCount += 1
      superBuildInputCount
    }

    def superBuildInputOverrideUsingSuper = Task.input {
      superBuildInputCount += 1
      superBuildInputCount
    }

    def superBuildTargetOverrideWithInput = Task {
      1234
    }
  }
  trait Build extends SuperBuild {
    var count = 0
    var changeOnceCount = 0
    var workerCloseCount = 0
    // Explicitly instantiate `Function1` objects to make sure we get
    // different instances each time
    def staticWorker: Worker[Int => Int] = Task.worker {
      new Function1[Int, Int] {
        def apply(v1: Int) = v1 + 1
      }
    }
    def changeOnceWorker: Worker[Int => Int] = Task.worker {
      new Function1[Int, Int] {
        def apply(v1: Int): Int = changeOnceInput() + v1
      }
    }
    def noisyWorker: Worker[Int => Int] = Task.worker {
      new Function1[Int, Int] {
        def apply(v1: Int) = input() + v1
      }
    }
    def noisyClosableWorker: Worker[(Int => Int) with AutoCloseable] = Task.worker {
      new Function1[Int, Int] with AutoCloseable {
        override def apply(v1: Int) = input() + v1
        override def close(): Unit = workerCloseCount += 1
      }
    }
    def changeOnceInput = Task.input {
      val ret = changeOnceCount
      if (changeOnceCount != 1) changeOnceCount = 1
      ret
    }
    def input = Task.input {
      count += 1
      count
    }
    def task = Task.anon {
      count += 1
      count
    }
    def taskInput = Task { input() }
    def taskNoInput = Task { task() }

    def persistent = Task.persistent {
      input() // force re-computation
      os.makeDir.all(Task.dest)
      os.write.append(Task.dest / "count", "hello\n")
      os.read.lines(Task.dest / "count").length
    }
    def nonPersistent = Task {
      input() // force re-computation
      os.makeDir.all(Task.dest)
      os.write.append(Task.dest / "count", "hello\n")
      os.read.lines(Task.dest / "count").length
    }

    def staticWorkerDownstream = Task {
      val w = staticWorker()
      w.apply(1)
    }

    def reevalTrigger = Task.input {
      new Object().hashCode()
    }
    def staticWorkerDownstreamReeval = Task {
      val w = staticWorker()
      reevalTrigger()
      w.apply(1)
    }

    def noisyWorkerDownstream = Task {
      val w = noisyWorker()
      w.apply(1)
    }
    def noisyClosableWorkerDownstream = Task {
      val w = noisyClosableWorker()
      w.apply(1)
    }
    def changeOnceWorkerDownstream = Task {
      val w = changeOnceWorker()
      w.apply(1)
    }

    override def superBuildInputOverrideWithConstant = Task { 123 }
    override def superBuildInputOverrideUsingSuper = Task {
      123 + super.superBuildInputOverrideUsingSuper()
    }

    var superBuildTargetOverrideWithInputCount = 0
    override def superBuildTargetOverrideWithInput = Task.input {
      superBuildTargetOverrideWithInputCount += 1
      superBuildTargetOverrideWithInputCount
    }

    // Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2958
    object repro2958 extends Module {
      val task1 = Task.anon { "task1" }
      def task2 = Task { task1() }
      def task3 = Task { task1() }
      def command() = Task.command {
        val t2 = task2()
        val t3 = task3()
        s"${t2},${t3}"
      }
    }
  }

  def withEnv(f: (Build, TestEvaluator) => Unit)(implicit tp: TestPath): Unit

  val tests = Tests {

    "inputs" - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a Target run once then are cached
      check.apply(build.taskInput) ==> Right((1, 1))
      check.apply(build.taskInput) ==> Right((2, 1))
      check.apply(build.taskInput) ==> Right((3, 1))
    }
    "noInputs" - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a Target run once then are cached
      check.apply(build.taskNoInput) ==> Right((1, 1))
      check.apply(build.taskNoInput) ==> Right((1, 0))
      check.apply(build.taskNoInput) ==> Right((1, 0))
    }

    "persistent" - withEnv { (build, check) =>
      // Persistent tasks keep the working dir around between runs
      println(build.millSourcePath.toString() + "\n")
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

    "overrideDifferentKind" - {
      "inputWithTarget" - {
        "notUsingSuper" - withEnv { (build, check) =>
          check.apply(build.superBuildInputOverrideWithConstant) ==> Right((123, 1))
          check.apply(build.superBuildInputOverrideWithConstant) ==> Right((123, 0))
          check.apply(build.superBuildInputOverrideWithConstant) ==> Right((123, 0))
        }
        "usingSuper" - withEnv { (build, check) =>
          check.apply(build.superBuildInputOverrideUsingSuper) ==> Right((124, 1))
          check.apply(build.superBuildInputOverrideUsingSuper) ==> Right((125, 1))
          check.apply(build.superBuildInputOverrideUsingSuper) ==> Right((126, 1))
        }
      }
      "targetWithInput" - withEnv { (build, check) =>
        check.apply(build.superBuildTargetOverrideWithInput) ==> Right((1, 0))
        check.apply(build.superBuildTargetOverrideWithInput) ==> Right((2, 0))
        check.apply(build.superBuildTargetOverrideWithInput) ==> Right((3, 0))
      }
    }
    "duplicateTaskInResult-issue2958" - withEnv { (build, check) =>
      check.apply(build.repro2958.command()) ==> Right(("task1,task1", 3))
    }
  }

}

object SeqTaskTests extends TaskTests {
  def withEnv(f: (Build, TestEvaluator) => Unit)(implicit tp: TestPath) = {
    object build extends Build{
      val millDiscover = Discover[this.type]
    }
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
    object build extends Build{
      val millDiscover = Discover[this.type]
    }
    val check = new TestEvaluator(
      build,
      threads = Some(16),
      extraPathEnd = Seq(getClass().getSimpleName())
    )
    f(build, check)
  }
}
