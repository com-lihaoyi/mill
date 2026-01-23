package mill.util

import mill.api.{ExecResult, Result, Val}
import mill.constants.{OutFolderMode}
import mill.constants.OutFiles.OutFiles
import mill.{Task, given}
import mill.api.{Cross, DefaultTaskModule, Discover, ExternalModule, Module, PathRef}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import mill.util.MainModule
import utest.{TestSuite, Tests, assert, test}

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.Properties

// External module for testing clean command with external workers
object TestExternalWorkerModule extends ExternalModule {
  def externalWorker = Task.Worker {
    new AutoCloseable {
      def close() = ()
      override def toString = "externalWorkerInstance"
    }
  }
  lazy val millDiscover = Discover[this.type]
}

object MainModuleTests extends TestSuite {

  object mainModule extends TestRootModule with MainModule {
    def hello = Task {
      System.out.println("Hello System Stdout")
      System.err.println("Hello System Stderr")
      Console.out.println("Hello Console Stdout")
      Console.err.println("Hello Console Stderr")
      Seq("hello", "world")
    }
    def hello2 = Task {
      System.out.println("Hello2 System Stdout")
      System.err.println("Hello2 System Stderr")
      Console.out.println("Hello2 Console Stdout")
      Console.err.println("Hello2 Console Stderr")
      Map("1" -> "hello", "2" -> "world")
    }
    def helloCommand(x: Int, y: Task[String]) = Task.Command { (x, y(), hello()) }

    /**
     * The hello worker
     */
    def helloWorker = Task.Worker {
      // non-JSON-serializable, but everything should work fine nonetheless
      new AutoCloseable {
        def close() = ()
        override def toString =
          "theHelloWorker"
      }
    }

    /** Sub module */
    object sub extends DefaultTaskModule {
      override def defaultTask(): String = "hello"
      def hello() = Task.Command {
        println("hello")
      }
      def moduleDeps = Seq(subSub)

      /** SubSub module */
      object subSub extends Module
    }

    override lazy val millDiscover = Discover[this.type]
  }

  object cleanModule extends TestRootModule with MainModule {

    trait Cleanable extends Module {
      def task = Task {
        os.write(Task.dest / "dummy.txt", "dummy text")
        Seq(PathRef(Task.dest))
      }
    }

    object foo extends Cleanable {
      object sub extends Cleanable
    }
    object bar extends Cleanable {
      override def task = Task {
        os.write(Task.dest / "dummy.txt", "dummy text")
        super.task() ++ Seq(PathRef(Task.dest))
      }
    }
    object bazz extends Cross[Bazz]("1", "2", "3")
    trait Bazz extends Cleanable with Cross.Module[String]

    def all = Task {
      foo.task()
      bar.task()
      bazz("1").task()
      bazz("2").task()
      bazz("3").task()
    }
    lazy val millDiscover = Discover[this.type]
  }

  object pathModule extends TestRootModule with MainModule {
    // Create a dependency graph:
    //   src1 -> mid1 -> dest1
    //   src2 -> mid2 -> dest2
    //   src3 -> mid1 (src3 also leads to dest1 via mid1)
    //   isolated (no connections)

    def base = Task { "base" }

    def mid1 = Task { base() + "-mid1" }
    def mid2 = Task { base() + "-mid2" }

    def src1 = Task { mid1() + "-src1" }
    def src2 = Task { mid2() + "-src2" }
    def src3 = Task { mid1() + "-src3" }

    def dest1 = Task { "dest1" }
    def dest2 = Task { "dest2" }

    // mid1 depends on dest1, mid2 depends on dest2
    // Actually, we need deps to go the other way for path finding
    // path finds: src -> ... -> dest means src depends on dest transitively
    // So: src1.inputs contains mid1, mid1.inputs contains dest1
    // Let's restructure:

    def leaf1 = Task { "leaf1" }
    def leaf2 = Task { "leaf2" }

    def inner1 = Task { leaf1() + "-inner1" }
    def inner2 = Task { leaf2() + "-inner2" }

    def outer1 = Task { inner1() + "-outer1" }
    def outer2 = Task { inner2() + "-outer2" }
    def outer3 = Task { inner1() + "-outer3" } // also depends on inner1 -> leaf1

    def isolated = Task { "isolated" }

    lazy val millDiscover = Discover[this.type]
  }

  class TestWorker(val name: String, workers: mutable.HashSet[TestWorker]) extends AutoCloseable {

    workers.synchronized {
      workers.add(this)
    }

    var closed = false
    def close(): Unit =
      if (!closed) {
        workers.synchronized {
          workers.remove(this)
        }
        closed = true
      }

    override def toString(): String =
      s"TestWorker($name)@${Integer.toHexString(System.identityHashCode(this))}"
  }

  class WorkerModule(workers: mutable.HashSet[TestWorker]) extends TestRootModule with MainModule {

    trait Cleanable extends Module {
      def theWorker = Task.Worker {
        new TestWorker("shared", workers)
      }
    }

    object foo extends Cleanable {
      object sub extends Cleanable
    }
    object bar extends Cleanable {
      override def theWorker = Task.Worker {
        new TestWorker("bar", workers)
      }
    }
    object bazz extends Cross[Bazz]("1", "2", "3")
    trait Bazz extends Cleanable with Cross.Module[String]

    def all = Task {
      foo.theWorker()
      bar.theWorker()
      bazz("1").theWorker()
      bazz("2").theWorker()
      bazz("3").theWorker()

      ()
    }
    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {

    test("inspect") {
      test("single") - UnitTester(mainModule, null).scoped { eval =>
        val res =
          eval.evaluator.execute(Seq(mainModule.inspect(eval.evaluator, "hello"))).executionResults
        val ExecResult.Success(Val(value: String)) = res.results.head.runtimeChecked
        assert(
          res.transitiveFailing.size == 0,
          value.startsWith("hello("),
          value.contains("MainModuleTests.scala:")
        )
      }
      test("multi") - UnitTester(mainModule, null).scoped { eval =>
        val res =
          eval.evaluator.execute(Seq(mainModule.inspect(
            eval.evaluator,
            "hello",
            "hello2"
          ))).executionResults
        val ExecResult.Success(Val(value: String)) = res.results.head.runtimeChecked
        assert(
          res.transitiveFailing.size == 0,
          value.startsWith("hello("),
          value.contains("MainModuleTests.scala:"),
          value.contains("\n\nhello2(")
        )
      }
      test("command") - UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("inspect", "helloCommand").runtimeChecked

        val Seq(res: String) = result.value.runtimeChecked
        assert(
          res.startsWith("helloCommand("),
          res.contains("MainModuleTests.scala:"),
          res.contains("hello")
        )
      }
      test("worker") - UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("inspect", "helloWorker").runtimeChecked

        val Seq(res: String) = result.value.runtimeChecked
        assert(
          res.startsWith("helloWorker("),
          res.contains("MainModuleTests.scala:"),
          res.contains("The hello worker"),
          res.contains("hello")
        )
      }
      test("module") - UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("inspect", "sub").runtimeChecked

        val Seq(res: String) = result.value.runtimeChecked
        assert(
          res.startsWith("sub("),
          res.contains("MainModuleTests.scala:"),
          res.contains("    Sub module"),
          res.contains("Inherited Modules:"),
          res.contains("Module Dependencies:"),
          res.contains("sub.subSub"),
          res.contains("Default Task: sub.hello"),
          res.contains("Tasks (re-/defined):"),
          res.contains("    sub.hello")
        )
      }
    }

    test("show") {
      def checkErrStream[T](errStream: ByteArrayOutputStream)(check: String => T): T = {
        def runCheck(): T =
          try {
            val strippedErr =
              fansi.Str(errStream.toString, errorMode = fansi.ErrorMode.Sanitize).plainText

            check(strippedErr)
          } catch {
            case ex: utest.AssertionError =>
              pprint.err.log(errStream.toString)
              throw ex
          }

        try runCheck()
        catch {
          case ex: utest.AssertionError if Properties.isWin =>
            // On Windows, it seems there can be a delay until the messages land in errStream,
            // it's worth retrying
            ex.printStackTrace(System.err)
            val waitFor = 2.seconds
            System.err.println(s"Caught $ex, trying again in $waitFor")
            Thread.sleep(waitFor.toMillis)
            runCheck()
        }
      }

      test("single") {
        val outStream = new ByteArrayOutputStream()
        val errStream = new ByteArrayOutputStream()
        UnitTester(
          mainModule,
          null,
          outStream = new PrintStream(outStream, true),
          errStream = new PrintStream(errStream, true)
        ).scoped { evaluator =>

          val results =
            evaluator.evaluator.execute(Seq(mainModule.show(
              evaluator.evaluator,
              "hello"
            ))).executionResults

          assert(results.transitiveFailing.size == 0)

          val ExecResult.Success(Val(value)) = results.results.head.runtimeChecked

          val shown = ujson.read(outStream.toByteArray)
          val expected = ujson.Arr.from(Seq("hello", "world"))
          assert(value == expected)
          assert(shown == expected)

          checkErrStream(errStream) { strippedErr =>
            assert(strippedErr.contains("Hello System Stdout"))
            assert(strippedErr.contains("Hello System Stderr"))
            assert(strippedErr.contains("Hello Console Stdout"))
            assert(strippedErr.contains("Hello Console Stderr"))
          }
        }
      }
      test("multi") {
        val outStream = new ByteArrayOutputStream()
        val errStream = new ByteArrayOutputStream()
        UnitTester(
          mainModule,
          null,
          outStream = new PrintStream(outStream, true),
          errStream = new PrintStream(errStream, true)
        ).scoped { evaluator =>

          val results =
            evaluator.evaluator.execute(Seq(mainModule.show(
              evaluator.evaluator,
              "hello",
              "+",
              "hello2"
            ))).executionResults

          assert(results.transitiveFailing.size == 0)

          val ExecResult.Success(Val(value)) = results.results.head.runtimeChecked

          val shown = ujson.read(outStream.toByteArray)

          val expected = ujson.Obj(
            "hello" -> ujson.Arr("hello", "world"),
            "hello2" -> ujson.Obj("1" -> "hello", "2" -> "world")
          )
          assert(value == expected)
          assert(shown == expected)

          checkErrStream(errStream) { strippedErr =>
            assert(strippedErr.contains("Hello System Stdout"))
            assert(strippedErr.contains("Hello System Stderr"))
            assert(strippedErr.contains("Hello Console Stdout"))
            assert(strippedErr.contains("Hello Console Stderr"))
            assert(strippedErr.contains("Hello2 System Stdout"))
            assert(strippedErr.contains("Hello2 System Stderr"))
            assert(strippedErr.contains("Hello2 Console Stdout"))
            assert(strippedErr.contains("Hello2 Console Stderr"))
          }
        }
      }

      test("command") {
        UnitTester(
          mainModule,
          null,
          outStream = new PrintStream(OutputStream.nullOutputStream(), true),
          errStream = new PrintStream(OutputStream.nullOutputStream(), true)
        ).scoped { evaluator =>

          val Left(ExecResult.Failure(msg = failureMsg)) =
            evaluator.apply("show", "helloCommand").runtimeChecked
          assert(
            failureMsg.contains("Expected Signature: helloCommand"),
            failureMsg.contains("-x <int>"),
            failureMsg.contains("-y <str>")
          )
          val Right(result) =
            evaluator.apply("show", "helloCommand", "-x", "1337", "-y", "lol").runtimeChecked

          val Seq(res) = result.value
          assert(res == ujson.Arr(1337, "lol", ujson.Arr("hello", "world")))
        }
      }

      test("worker") {
        UnitTester(
          mainModule,
          null,
          outStream = new PrintStream(OutputStream.nullOutputStream(), true),
          errStream = new PrintStream(OutputStream.nullOutputStream(), true)
        ).scoped { evaluator =>

          val Right(result) = evaluator.apply("show", "helloWorker").runtimeChecked
          val Seq(res: ujson.Obj) = result.value.runtimeChecked
          assert(res("toString").str == "theHelloWorker")
          assert(res("worker").str == "helloWorker")
          assert(res("inputsHash").numOpt.isDefined)
        }
      }
    }

    test("showNamed") {

      test("single") {
        UnitTester(mainModule, null).scoped { evaluator =>
          val results =
            evaluator.evaluator.execute(Seq(mainModule.showNamed(
              evaluator.evaluator,
              "hello"
            ))).executionResults

          assert(results.transitiveFailing.size == 0)

          val ExecResult.Success(Val(value)) = results.results.head.runtimeChecked

          assert(value == ujson.Obj.from(Map(
            "hello" -> ujson.Arr.from(Seq("hello", "world"))
          )))
        }
      }
      test("multi") {
        UnitTester(mainModule, null).scoped { evaluator =>
          val results =
            evaluator.evaluator.execute(Seq(mainModule.showNamed(
              evaluator.evaluator,
              "hello",
              "+",
              "hello2"
            ))).executionResults

          assert(results.transitiveFailing.size == 0)

          val ExecResult.Success(Val(value)) = results.results.head.runtimeChecked

          assert(value == ujson.Obj.from(Map(
            "hello" -> ujson.Arr.from(Seq("hello", "world")),
            "hello2" -> ujson.Obj.from(Map("1" -> "hello", "2" -> "world"))
          )))
        }
      }
    }

    test("resolve") {
      UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("resolve", "_").runtimeChecked

        val Seq(res: Seq[String]) = result.value.runtimeChecked
        assert(res.contains("hello"))
        assert(res.contains("hello2"))
        assert(res.contains("helloCommand"))
        assert(res.contains("helloWorker"))
      }
    }

    test("path") {
      // Graph structure:
      // outer1 -> inner1 -> leaf1
      // outer2 -> inner2 -> leaf2
      // outer3 -> inner1 -> leaf1
      // isolated (no deps)

      test("simple") - UnitTester(pathModule, null).scoped { eval =>
        // Single src to single dest
        val Right(result) = eval.apply("path", "outer1", "leaf1").runtimeChecked
        val Seq(labels: List[String]) = result.value.runtimeChecked
        assert(labels == List("outer1", "inner1", "leaf1"))
      }

      test("multiSrc") - UnitTester(pathModule, null).scoped { eval =>
        // Multiple sources (outer1 and outer3 both depend on leaf1 via inner1)
        // Using glob pattern to match multiple sources
        val Right(result) = eval.apply("path", "{outer1,outer3}", "leaf1").runtimeChecked
        val Seq(labels: List[String]) = result.value.runtimeChecked
        // Should find path from either outer1 or outer3 to leaf1
        assert(labels.last == "leaf1")
        assert(labels.head == "outer1" || labels.head == "outer3")
        assert(labels.contains("inner1"))
      }

      test("multiDest") - UnitTester(pathModule, null).scoped { eval =>
        // Single source, multiple possible destinations
        // outer1 depends on leaf1 but not leaf2
        val Right(result) = eval.apply("path", "outer1", "{leaf1,leaf2}").runtimeChecked
        val Seq(labels: List[String]) = result.value.runtimeChecked
        // Should find path to leaf1 (not leaf2)
        assert(labels == List("outer1", "inner1", "leaf1"))
      }

      test("multiSrcMultiDest") - UnitTester(pathModule, null).scoped { eval =>
        // Multiple sources and multiple destinations
        // outer1,outer3 -> inner1 -> leaf1
        // outer2 -> inner2 -> leaf2
        val Right(result) = eval.apply("path", "{outer1,outer2}", "{leaf1,leaf2}").runtimeChecked
        val Seq(labels: List[String]) = result.value.runtimeChecked
        // Should find a path from one of the sources to one of the destinations
        assert(labels.nonEmpty)
        assert(
          (labels.head == "outer1" && labels.last == "leaf1") ||
            (labels.head == "outer2" && labels.last == "leaf2")
        )
      }

      test("noPath") - UnitTester(pathModule, null).scoped { eval =>
        // isolated has no dependencies, so no path to leaf1
        val Left(result) = eval.apply("path", "isolated", "leaf1").runtimeChecked
        assert(result.toString.contains("No path found"))
      }

      test("noPathMulti") - UnitTester(pathModule, null).scoped { eval =>
        // isolated has no path to any leaf
        val Left(result) = eval.apply("path", "isolated", "{leaf1,leaf2}").runtimeChecked
        assert(result.toString.contains("No path found"))
      }
    }

    test("clean") {

      def checkExists(out: os.Path, exists: Boolean)(paths: os.SubPath*): Unit = {
        paths.foreach { path =>
          assert(os.exists(out / path) == exists)
        }
      }

      test("all") {
        UnitTester(cleanModule, null).scoped { ev =>
          val out = ev.evaluator.outPath
          val r1 = ev.evaluator.execute(Seq(cleanModule.all)).executionResults
          assert(r1.transitiveFailing.size == 0)
          checkExists(out, true)(os.sub / "foo")

          val r2 = ev.evaluator.execute(Seq(cleanModule.clean(ev.evaluator))).executionResults
          assert(r2.transitiveFailing.size == 0)
          checkExists(out, false)(os.sub / "foo")
        }
      }

      test("single-task") {
        UnitTester(cleanModule, null).scoped { ev =>
          val out = ev.evaluator.outPath
          val r1 = ev.evaluator.execute(Seq(cleanModule.all)).executionResults
          assert(r1.transitiveFailing.size == 0)
          checkExists(out, true)(
            os.sub / "foo/task.json",
            os.sub / "foo/task.dest/dummy.txt",
            os.sub / "bar/task.json",
            os.sub / "bar/task.dest/dummy.txt"
          )

          val r2 =
            ev.evaluator.execute(Seq(cleanModule.clean(
              ev.evaluator,
              "foo.task"
            ))).executionResults
          assert(r2.transitiveFailing.size == 0)
          checkExists(out, false)(
            os.sub / "foo/task.log",
            os.sub / "foo/task.json",
            os.sub / "foo/task.dest/dummy.txt"
          )
          checkExists(out, true)(
            os.sub / "bar/task.json",
            os.sub / "bar/task.dest/dummy.txt"
          )
        }
      }

      test("single-module") {
        UnitTester(cleanModule, null).scoped { ev =>
          val out = ev.evaluator.outPath
          val r1 = ev.evaluator.execute(Seq(cleanModule.all)).executionResults
          assert(r1.transitiveFailing.size == 0)
          checkExists(out, true)(
            os.sub / "foo/task.json",
            os.sub / "foo/task.dest/dummy.txt",
            os.sub / "bar/task.json",
            os.sub / "bar/task.dest/dummy.txt"
          )

          val r2 =
            ev.evaluator.execute(Seq(cleanModule.clean(ev.evaluator, "bar"))).executionResults
          assert(r2.transitiveFailing.size == 0)
          checkExists(out, true)(
            os.sub / "foo/task.json",
            os.sub / "foo/task.dest/dummy.txt"
          )
          checkExists(out, false)(
            os.sub / "bar/task.json",
            os.sub / "bar/task.dest/dummy.txt"
          )
        }
      }
    }

    test("cleanWorker") {
      test("all") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        UnitTester(workerModule, null).scoped { ev =>

          val r1 = ev.evaluator.execute(Seq(workerModule.all)).executionResults
          assert(r1.transitiveFailing.size == 0)
          assert(workers.size == 5)

          val r2 = ev.evaluator.execute(Seq(workerModule.clean(ev.evaluator))).executionResults
          assert(r2.transitiveFailing.size == 0)
          assert(workers.isEmpty)
        }
      }

      test("single-task") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        UnitTester(workerModule, null).scoped { ev =>

          val r1 = ev.evaluator.execute(Seq(workerModule.all)).executionResults
          assert(r1.transitiveFailing.size == 0)
          assert(workers.size == 5)

          val r2 = ev.evaluator.execute(Seq(workerModule.clean(
            ev.evaluator,
            "foo.theWorker"
          ))).executionResults
          assert(r2.transitiveFailing.size == 0)
          assert(workers.size == 4)

          val r3 = ev.evaluator.execute(Seq(workerModule.clean(
            ev.evaluator,
            "bar.theWorker"
          ))).executionResults
          assert(r3.transitiveFailing.size == 0)
          assert(workers.size == 3)

          val r4 = ev.evaluator.execute(Seq(workerModule.clean(
            ev.evaluator,
            "bazz[1].theWorker"
          ))).executionResults
          assert(r4.transitiveFailing.size == 0)
          assert(workers.size == 2)
        }
      }

      test("single-task via rm") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        UnitTester(workerModule, null).scoped { ev =>

          ev.evaluator.execute(Seq(workerModule.foo.theWorker)).executionResults
            .ensuring(_.transitiveFailing.size == 0)
          assert(workers.size == 1)

          val originalFooWorker = workers.head

          ev.evaluator.execute(Seq(workerModule.bar.theWorker)).executionResults
            .ensuring(_.transitiveFailing.size == 0)
          assert(workers.size == 2)
          assert(workers.exists(_ eq originalFooWorker))

          val originalBarWorker = workers.filter(_ ne originalFooWorker).head

          ev.evaluator.execute(Seq(workerModule.foo.theWorker)).executionResults
            .ensuring(_.transitiveFailing.size == 0)
          assert(workers.size == 2)
          assert(workers.exists(_ eq originalFooWorker))

          ev.evaluator.execute(Seq(workerModule.bar.theWorker)).executionResults
            .ensuring(_.transitiveFailing.size == 0)
          assert(workers.size == 2)
          assert(workers.exists(_ eq originalBarWorker))

          val outDir = os.Path(OutFiles.outFor(OutFolderMode.REGULAR), workerModule.moduleDir)

          assert(!originalFooWorker.closed)
          os.remove(outDir / "foo/theWorker.json")

          ev.evaluator.execute(Seq(workerModule.foo.theWorker)).executionResults
            .ensuring(_.transitiveFailing.size == 0)
          assert(workers.size == 2)
          assert(!workers.exists(_ eq originalFooWorker))
          assert(originalFooWorker.closed)

          assert(!originalBarWorker.closed)
          os.remove(outDir / "bar/theWorker.json")

          ev.evaluator.execute(Seq(workerModule.bar.theWorker)).executionResults
            .ensuring(_.transitiveFailing.size == 0)
          assert(workers.size == 2)
          assert(!workers.exists(_ eq originalBarWorker))
          assert(originalBarWorker.closed)
        }
      }
      test("single-module") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        UnitTester(workerModule, null).scoped { ev =>

          val r1 = ev.evaluator.execute(Seq(workerModule.all)).executionResults
          assert(r1.transitiveFailing.size == 0)
          assert(workers.size == 5)

          val r2 =
            ev.evaluator.execute(Seq(workerModule.clean(ev.evaluator, "foo"))).executionResults
          assert(r2.transitiveFailing.size == 0)
          assert(workers.size == 4)

          val r3 =
            ev.evaluator.execute(Seq(workerModule.clean(ev.evaluator, "bar"))).executionResults
          assert(r3.transitiveFailing.size == 0)
          assert(workers.size == 3)

          val r4 =
            ev.evaluator.execute(Seq(workerModule.clean(ev.evaluator, "bazz[1]"))).executionResults
          assert(r4.transitiveFailing.size == 0)
          assert(workers.size == 2)
        }
      }

      test("external-module") {
        // Test cleaning external module workers (issue #6549)
        // External modules have segments ending with '/' which need special handling
        object externalModuleBuild extends TestRootModule with MainModule {
          def useExternalWorker = Task {
            TestExternalWorkerModule.externalWorker()
            "done"
          }
          lazy val millDiscover = Discover[this.type]
        }
        UnitTester(externalModuleBuild, null).scoped { ev =>
          // First run the task that uses the external worker
          val r1 = ev.evaluator.execute(Seq(externalModuleBuild.useExternalWorker)).executionResults
          assert(r1.transitiveFailing.size == 0)

          // Now try to clean the external worker - this should not throw an error
          // Previously this would fail with: "[mill.util.TestExternalWorkerModule/] is not a valid path segment"
          val r2 = ev.evaluator.execute(Seq(externalModuleBuild.clean(
            ev.evaluator,
            "mill.util.TestExternalWorkerModule/externalWorker"
          ))).executionResults
          assert(r2.transitiveFailing.size == 0)
        }
      }
    }
  }
}
