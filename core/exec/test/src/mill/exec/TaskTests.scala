package mill.exec

import utest.*
import mill.{Task, Worker}
import mill.api.{Discover, Module}
import mill.testkit.UnitTester
import mill.testkit.UnitTester.Result
import mill.testkit.TestRootModule
import utest.framework.TestPath
import mill.api.ExecResult

trait TaskTests extends TestSuite {
  trait SuperBuild extends TestRootModule {

    var superBuildInputCount = 0

    def superBuildInputOverrideWithConstant = Task.Input {
      superBuildInputCount += 1
      superBuildInputCount
    }

    def superBuildInputOverrideUsingSuper = Task.Input {
      superBuildInputCount += 1
      superBuildInputCount
    }

    def superBuildTaskOverrideWithInput = Task {
      1234
    }
  }
  trait Build extends SuperBuild {
    var count = 0
    var changeOnceCount = 0
    var workerCloseCount = 0
    // Explicitly instantiate `Function1` objects to make sure we get
    // different instances each time
    def staticWorker: Worker[Int => Int] = Task.Worker {
      new Function1[Int, Int] {
        def apply(v1: Int) = v1 + 1
      }
    }
    def changeOnceWorker: Worker[Int => Int] = Task.Worker {
      new Function1[Int, Int] {
        def apply(v1: Int): Int = changeOnceInput() + v1
      }
    }
    def noisyWorker: Worker[Int => Int] = Task.Worker {
      new Function1[Int, Int] {
        def apply(v1: Int) = input() + v1
      }
    }
    def noisyClosableWorker: Worker[(Int => Int) & AutoCloseable] = Task.Worker {
      new Function1[Int, Int] with AutoCloseable {
        override def apply(v1: Int) = input() + v1
        override def close(): Unit = workerCloseCount += 1
      }
    }
    def changeOnceInput = Task.Input {
      val ret = changeOnceCount
      if (changeOnceCount != 1) changeOnceCount = 1
      ret
    }
    def input = Task.Input {
      count += 1
      count
    }
    def task = Task.Anon {
      count += 1
      count
    }
    def taskInput = Task { input() }
    def taskNoInput = Task { task() }

    def persistent = Task(persistent = true) {
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

    def reevalTrigger = Task.Input {
      Object().hashCode()
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

    var superBuildTaskOverrideWithInputCount = 0
    override def superBuildTaskOverrideWithInput = Task.Input {
      superBuildTaskOverrideWithInputCount += 1
      superBuildTaskOverrideWithInputCount
    }

    // A task that can fail by using Result.Failure
    def sometimesFailing(fail: Boolean): Task[String] = Task.Anon {
      if (!fail) mill.api.Result.Success("Success")
      else mill.api.Result.Failure("Failure")
    }

    // A task that can fail by using Task.fail API
    // this is the high-level API for [[sometimesFailing]]
    def sometimesFailingWithException(fail: Boolean): Task[String] = Task.Anon {
      if (!fail) "Success"
      else Task.fail("Failure")
    }

    // Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2958
    object repro2958 extends Module {
      val task1 = Task.Anon { "task1" }
      def task2 = Task { task1() }
      def task3 = Task { task1() }
      def command() = Task.Command {
        val t2 = task2()
        val t3 = task3()
        s"${t2},${t3}"
      }
    }
  }

  def withEnv(f: (Build, UnitTester) => Unit)(using tp: TestPath): Unit

  val tests = Tests {

    test("inputs") - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Tasks
      // to re-evaluate, but normal Tasks behind a Task run once then are cached
      check(build.taskInput) ==> Right(Result(1, 1))
      check(build.taskInput) ==> Right(Result(2, 1))
      check(build.taskInput) ==> Right(Result(3, 1))
    }
    test("noInputs") - withEnv { (build, check) =>
      // Inputs always re-evaluate, including forcing downstream cached Tasks
      // to re-evaluate, but normal Tasks behind a Task run once then are cached
      check(build.taskNoInput) ==> Right(Result(1, 1))
      check(build.taskNoInput) ==> Right(Result(1, 0))
      check(build.taskNoInput) ==> Right(Result(1, 0))
    }

    test("persistent") - withEnv { (build, check) =>
      // Persistent tasks keep the working dir around between runs
      println(build.moduleDir.toString() + "\n")
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
        val wc = check.execution.workerCache

        check(build.staticWorkerDownstream) ==> Right(Result(2, 1))
        wc.size ==> 1
        val firstCached = wc.head

        check(build.staticWorkerDownstream) ==> Right(Result(2, 0))
        wc.head ==> firstCached
        check(build.staticWorkerDownstream) ==> Right(Result(2, 0))
        wc.head ==> firstCached
      }
      test("staticButReevaluated") - withEnv { (build, check) =>
        val wc = check.execution.workerCache

        check(build.staticWorkerDownstreamReeval) ==> Right(Result(2, 1))
        check.execution.workerCache.size ==> 1
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
        val wc = check.execution.workerCache

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
        val wc = check.execution.workerCache

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
      test("inputWithTask") {
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
      test("taskWithInput") - withEnv { (build, check) =>
        check(build.superBuildTaskOverrideWithInput) ==> Right(Result(1, 0))
        check(build.superBuildTaskOverrideWithInput) ==> Right(Result(2, 0))
        check(build.superBuildTaskOverrideWithInput) ==> Right(Result(3, 0))
      }
    }
    test("duplicateTaskInResult-issue2958") - withEnv { (build, check) =>
      check(build.repro2958.command()) ==> Right(Result("task1,task1", 3))
    }

    test("sometimeFailing") {
      test("success") - withEnv { (build, check) =>
        check(build.sometimesFailing(false)) ==> Right(Result("Success", 0))
      }
      test("failure") - withEnv { (build, check) =>
        val Left(ExecResult.Failure(msg = "Failure")) = check(build.sometimesFailing(true))
      }
    }
    test("sometimeFailingWithException") {
      test("success") - withEnv { (build, check) =>
        check(build.sometimesFailingWithException(false)) ==> Right(Result("Success", 0))
      }
      test("failure") - withEnv { (build, check) =>
        val Left(ExecResult.Failure(msg = "Failure")) =
          check(build.sometimesFailingWithException(true))
      }
    }
  }

}

object SeqTaskTests extends TaskTests {
  def withEnv(f: (Build, UnitTester) => Unit)(using tp: TestPath) = {
    object build extends Build {
      lazy val millDiscover = Discover[this.type]
    }
    UnitTester(build, null, threads = Some(1)).scoped { check =>
      f(build, check)
    }
  }
}
object ParTaskTests extends TaskTests {
  def withEnv(f: (Build, UnitTester) => Unit)(using tp: TestPath) = {
    object build extends Build {
      lazy val millDiscover = Discover[this.type]
    }
    UnitTester(build, null, threads = Some(16)).scoped { check =>
      f(build, check)
    }
  }
}
