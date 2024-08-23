package mill.eval

import utest._
import mill.T
import mill.define.{Module, Worker}
import mill.testkit.UnitTester
import mill.testkit.UnitTester.Result
import mill.testkit.TestBaseModule
import utest.framework.TestPath

trait TaskTests extends TestSuite {
  trait SuperBuild extends TestBaseModule {

    var superBuildInputCount = 0

    def superBuildInputOverrideWithConstant = T.input {
      superBuildInputCount += 1
      superBuildInputCount
    }

    def superBuildInputOverrideUsingSuper = T.input {
      superBuildInputCount += 1
      superBuildInputCount
    }

    def superBuildTargetOverrideWithInput = T {
      1234
    }
  }
  trait Build extends SuperBuild {
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

    override def superBuildInputOverrideWithConstant = T { 123 }
    override def superBuildInputOverrideUsingSuper = T {
      123 + super.superBuildInputOverrideUsingSuper()
    }

    var superBuildTargetOverrideWithInputCount = 0
    override def superBuildTargetOverrideWithInput = T.input {
      superBuildTargetOverrideWithInputCount += 1
      superBuildTargetOverrideWithInputCount
    }

    // Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2958
    object repro2958 extends Module {
      val task1 = T.task { "task1" }
      def task2 = T { task1() }
      def task3 = T { task1() }
      def command() = T.command {
        val t2 = task2()
        val t3 = task3()
        s"${t2},${t3}"
      }
    }
  }

  def withEnv(f: (Build, UnitTester) => Unit)(implicit tp: TestPath): Unit

  val tests = Tests {

    test("inputs") - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a Target run once then are cached
      check(build.taskInput) ==> Right(Result(1, 1))
      check(build.taskInput) ==> Right(Result(2, 1))
      check(build.taskInput) ==> Right(Result(3, 1))
    }
    test("noInputs") - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a Target run once then are cached
      check(build.taskNoInput) ==> Right(Result(1, 1))
      check(build.taskNoInput) ==> Right(Result(1, 0))
      check(build.taskNoInput) ==> Right(Result(1, 0))
    }

    test("persistent") - withEnv { (build, check) =>
      // Persistent tasks keep the working dir around between runs
      println(build.millSourcePath.toString() + "\n")
      check(build.persistent) ==> Right(Result(1, 1))
      check(build.persistent) ==> Right(Result(2, 1))
      check(build.persistent) ==> Right(Result(3, 1))
    }
    test("nonPersistent") - withEnv { (build, check) =>
      // non-Persistent tasks keep the working dir around between runs
      check(build.nonPersistent) ==> Right(Result(1, 1))
      check(build.nonPersistent) ==> Right(Result(1, 1))
      check(build.nonPersistent) ==> Right(Result(1, 1))
    }

    test("worker") {
      test("static") - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check(build.staticWorkerDownstream) ==> Right(Result(2, 1))
        wc.size ==> 1
        val firstCached = wc.head

        check(build.staticWorkerDownstream) ==> Right(Result(2, 0))
        wc.head ==> firstCached
        check(build.staticWorkerDownstream) ==> Right(Result(2, 0))
        wc.head ==> firstCached
      }
      test("staticButReevaluated") - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check(build.staticWorkerDownstreamReeval) ==> Right(Result(2, 1))
        check.evaluator.workerCache.size ==> 1
        val firstCached = wc.head

        check(build.staticWorkerDownstreamReeval) ==> Right(Result(2, 1))
        wc.head ==> firstCached
        check(build.staticWorkerDownstreamReeval) ==> Right(Result(2, 1))
        wc.head ==> firstCached
      }
      test("changedOnce") - withEnv { (build, check) =>
        check(build.changeOnceWorkerDownstream) ==> Right(Result(1, 1))
        // changed
        check(build.changeOnceWorkerDownstream) ==> Right(Result(2, 1))
        check(build.changeOnceWorkerDownstream) ==> Right(Result(2, 0))
      }
      test("alwaysChanged") - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check(build.noisyWorkerDownstream) ==> Right(Result(2, 1))
        wc.size ==> 1
        val firstCached = wc.head

        check(build.noisyWorkerDownstream) ==> Right(Result(3, 1))
        wc.size ==> 1
        assert(wc.head != firstCached)
        val secondCached = wc.head

        check(build.noisyWorkerDownstream) ==> Right(Result(4, 1))
        wc.size ==> 1
        assert(wc.head != secondCached)
      }
      test("closableWorker") - withEnv { (build, check) =>
        val wc = check.evaluator.workerCache

        check(build.noisyClosableWorkerDownstream) ==> Right(Result(2, 1))
        wc.size ==> 1
        build.workerCloseCount ==> 0

        val firstCached = wc.head

        check(build.noisyClosableWorkerDownstream) ==> Right(Result(3, 1))
        wc.size ==> 1
        build.workerCloseCount ==> 1
        assert(wc.head != firstCached)

        val secondCached = wc.head

        check(build.noisyClosableWorkerDownstream) ==> Right(Result(4, 1))
        wc.size ==> 1
        assert(wc.head != secondCached)
      }
    }

    test("overrideDifferentKind") {
      test("inputWithTarget") {
        test("notUsingSuper") - withEnv { (build, check) =>
          check(build.superBuildInputOverrideWithConstant) ==> Right(Result(123, 1))
          check(build.superBuildInputOverrideWithConstant) ==> Right(Result(123, 0))
          check(build.superBuildInputOverrideWithConstant) ==> Right(Result(123, 0))
        }
        test("usingSuper") - withEnv { (build, check) =>
          check(build.superBuildInputOverrideUsingSuper) ==> Right(Result(124, 1))
          check(build.superBuildInputOverrideUsingSuper) ==> Right(Result(125, 1))
          check(build.superBuildInputOverrideUsingSuper) ==> Right(Result(126, 1))
        }
      }
      test("targetWithInput") - withEnv { (build, check) =>
        check(build.superBuildTargetOverrideWithInput) ==> Right(Result(1, 0))
        check(build.superBuildTargetOverrideWithInput) ==> Right(Result(2, 0))
        check(build.superBuildTargetOverrideWithInput) ==> Right(Result(3, 0))
      }
    }
    test("duplicateTaskInResult-issue2958") - withEnv { (build, check) =>
      check(build.repro2958.command()) ==> Right(Result("task1,task1", 3))
    }
  }

}

object SeqTaskTests extends TaskTests {
  def withEnv(f: (Build, UnitTester) => Unit)(implicit tp: TestPath) = {
    object build extends Build
    val check = UnitTester(build, null, threads = Some(1))
    f(build, check)
  }
}
object ParTaskTests extends TaskTests {
  def withEnv(f: (Build, UnitTester) => Unit)(implicit tp: TestPath) = {
    object build extends Build
    val check = UnitTester(build, null, threads = Some(16))
    f(build, check)
  }
}
