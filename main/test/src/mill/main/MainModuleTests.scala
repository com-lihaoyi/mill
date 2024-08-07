package mill.main

import mill.given
import mill.api.{PathRef, Result, Val}
import mill.{Agg, T, Task, given}
import mill.define.{Cross, Discover, Module}
import mill.main.client.OutFiles
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.{TestSuite, Tests, assert, test}

import java.io.{ByteArrayOutputStream, PrintStream}

import scala.collection.mutable

object MainModuleTests extends TestSuite {

  object mainModule extends TestBaseModule with MainModule {
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
    override lazy val millDiscover: Discover = Discover[this.type]
  }

  object cleanModule extends TestBaseModule with MainModule {

    trait Cleanable extends Module {
      def target = Task {
        os.write(T.dest / "dummy.txt", "dummy text")
        Seq(PathRef(T.dest))
      }
    }

    object foo extends Cleanable {
      object sub extends Cleanable
    }
    object bar extends Cleanable {
      override def target = Task {
        os.write(T.dest / "dummy.txt", "dummy text")
        super.target() ++ Seq(PathRef(T.dest))
      }
    }
    object bazz extends Cross[Bazz]("1", "2", "3")
    trait Bazz extends Cleanable with Cross.Module[String]

    def all = Task {
      foo.target()
      bar.target()
      bazz("1").target()
      bazz("2").target()
      bazz("3").target()
    }
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

  class WorkerModule(workers: mutable.HashSet[TestWorker]) extends TestBaseModule with MainModule {

    trait Cleanable extends Module {
      def theWorker = Task.Worker {
        new TestWorker("shared", workers)
      }
    }

    object foo extends Cleanable {
      object sub extends Cleanable
    }
    object bar extends Cleanable {
      def theWorker = Task.Worker {
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
  }

  override def tests: Tests = Tests {

    test("inspect") {
      test("single") - UnitTester(mainModule, null).scoped { eval =>
        val res = eval.evaluator.evaluate(Agg(mainModule.inspect(eval.evaluator, "hello")))
        val Result.Success(Val(value: String)) = res.rawValues.head
        assert(
          res.failing.keyCount == 0,
          value.startsWith("hello("),
          value.contains("MainModuleTests.scala:")
        )
      }
      test("multi") - UnitTester(mainModule, null).scoped { eval =>
        val res =
          eval.evaluator.evaluate(Agg(mainModule.inspect(eval.evaluator, "hello", "hello2")))
        val Result.Success(Val(value: String)) = res.rawValues.head
        assert(
          res.failing.keyCount == 0,
          value.startsWith("hello("),
          value.contains("MainModuleTests.scala:"),
          value.contains("\n\nhello2(")
        )
      }
      test("command") - UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("inspect", "helloCommand")

        val Seq(res: String) = result.value
        assert(
          res.startsWith("helloCommand("),
          res.contains("MainModuleTests.scala:"),
          res.contains("hello")
        )
      }
      test("worker") - UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("inspect", "helloWorker")

        val Seq(res: String) = result.value
        assert(
          res.startsWith("helloWorker("),
          res.contains("MainModuleTests.scala:"),
          res.contains("The hello worker"),
          res.contains("hello")
        )
      }
    }

    test("show") {
      val outStream = new ByteArrayOutputStream()
      val errStream = new ByteArrayOutputStream()
      val evaluator = UnitTester(
        mainModule,
        null,
        outStream = new PrintStream(outStream, true),
        errStream = new PrintStream(errStream, true)
      )
      test("single") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.show(evaluator.evaluator, "hello")))

        assert(results.failing.keyCount == 0)

        val Result.Success(Val(value)) = results.rawValues.head

        val shown = ujson.read(outStream.toByteArray)
        val expected = ujson.Arr.from(Seq("hello", "world"))
        assert(value == expected)
        assert(shown == expected)

        // Make sure both stdout and stderr are redirected by `show`
        // to stderr so that only the JSON file value goes to stdout
        val strippedErr =
          fansi.Str(errStream.toString, errorMode = fansi.ErrorMode.Sanitize).plainText

        assert(strippedErr.contains("Hello System Stdout"))
        assert(strippedErr.contains("Hello System Stderr"))
        assert(strippedErr.contains("Hello Console Stdout"))
        assert(strippedErr.contains("Hello Console Stderr"))
      }
      test("multi") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.show(
            evaluator.evaluator,
            "hello",
            "+",
            "hello2"
          )))

        assert(results.failing.keyCount == 0)

        val Result.Success(Val(value)) = results.rawValues.head

        val shown = ujson.read(outStream.toByteArray)

        val expected = ujson.Obj(
          "hello" -> ujson.Arr("hello", "world"),
          "hello2" -> ujson.Obj("1" -> "hello", "2" -> "world")
        )
        assert(value == expected)
        assert(shown == expected)

        // Make sure both stdout and stderr are redirected by `show`
        // to stderr so that only the JSON file value goes to stdout
        val strippedErr =
          fansi.Str(errStream.toString, errorMode = fansi.ErrorMode.Sanitize).plainText

        assert(strippedErr.contains("Hello2 System Stdout"))
        assert(strippedErr.contains("Hello2 System Stderr"))
        assert(strippedErr.contains("Hello2 Console Stdout"))
        assert(strippedErr.contains("Hello2 Console Stderr"))
      }

      test("command") {
        val Left(Result.Failure(failureMsg, _)) = evaluator.apply("show", "helloCommand")
        assert(
          failureMsg.contains("Expected Signature: helloCommand"),
          failureMsg.contains("-x <int>"),
          failureMsg.contains("-y <str>")
        )
        val Right(result) =
          evaluator.apply("show", "helloCommand", "-x", "1337", "-y", "lol")

        val Seq(res) = result.value
        assert(res == ujson.Arr(1337, "lol", ujson.Arr("hello", "world")))
      }

      test("worker") {
        val Right(result) = evaluator.apply("show", "helloWorker")
        val Seq(res: ujson.Obj) = result.value
        assert(res("toString").str == "theHelloWorker")
        assert(res("worker").str == "helloWorker")
        assert(res("inputsHash").numOpt.isDefined)
      }
    }

    test("showNamed") {
      val evaluator = UnitTester(mainModule, null)
      test("single") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.showNamed(evaluator.evaluator, "hello")))

        assert(results.failing.keyCount == 0)

        val Result.Success(Val(value)) = results.rawValues.head

        assert(value == ujson.Obj.from(Map(
          "hello" -> ujson.Arr.from(Seq("hello", "world"))
        )))
      }
      test("multi") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.showNamed(
            evaluator.evaluator,
            "hello",
            "+",
            "hello2"
          )))

        assert(results.failing.keyCount == 0)

        val Result.Success(Val(value)) = results.rawValues.head

        assert(value == ujson.Obj.from(Map(
          "hello" -> ujson.Arr.from(Seq("hello", "world")),
          "hello2" -> ujson.Obj.from(Map("1" -> "hello", "2" -> "world"))
        )))
      }
    }

    test("resolve") {
      UnitTester(mainModule, null).scoped { eval =>
        val Right(result) = eval.apply("resolve", "_")

        val Seq(res: Seq[String]) = result.value
        assert(res.contains("hello"))
        assert(res.contains("hello2"))
        assert(res.contains("helloCommand"))
        assert(res.contains("helloWorker"))
      }
    }

    test("clean") {
      val ev = UnitTester(cleanModule, null)
      val out = ev.evaluator.outPath

      def checkExists(exists: Boolean)(paths: os.SubPath*): Unit = {
        paths.foreach { path =>
          assert(os.exists(out / path) == exists)
        }
      }

      test("all") {
        val r1 = ev.evaluator.evaluate(Agg(cleanModule.all))
        assert(r1.failing.keyCount == 0)
        checkExists(true)(os.sub / "foo")

        val r2 = ev.evaluator.evaluate(Agg(cleanModule.clean(ev.evaluator)))
        assert(r2.failing.keyCount == 0)
        checkExists(false)(os.sub / "foo")
      }

      test("single-target") {
        val r1 = ev.evaluator.evaluate(Agg(cleanModule.all))
        assert(r1.failing.keyCount == 0)
        checkExists(true)(
          os.sub / "foo/target.json",
          os.sub / "foo/target.dest/dummy.txt",
          os.sub / "bar/target.json",
          os.sub / "bar/target.dest/dummy.txt"
        )

        val r2 = ev.evaluator.evaluate(Agg(cleanModule.clean(ev.evaluator, "foo.target")))
        assert(r2.failing.keyCount == 0)
        checkExists(false)(
          os.sub / "foo/target.log",
          os.sub / "foo/target.json",
          os.sub / "foo/target.dest/dummy.txt"
        )
        checkExists(true)(
          os.sub / "bar/target.json",
          os.sub / "bar/target.dest/dummy.txt"
        )
      }

      test("single-module") {
        val r1 = ev.evaluator.evaluate(Agg(cleanModule.all))
        assert(r1.failing.keyCount == 0)
        checkExists(true)(
          os.sub / "foo/target.json",
          os.sub / "foo/target.dest/dummy.txt",
          os.sub / "bar/target.json",
          os.sub / "bar/target.dest/dummy.txt"
        )

        val r2 = ev.evaluator.evaluate(Agg(cleanModule.clean(ev.evaluator, "bar")))
        assert(r2.failing.keyCount == 0)
        checkExists(true)(
          os.sub / "foo/target.json",
          os.sub / "foo/target.dest/dummy.txt"
        )
        checkExists(false)(
          os.sub / "bar/target.json",
          os.sub / "bar/target.dest/dummy.txt"
        )
      }
    }

    test("cleanWorker") {
      test("all") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        val ev = UnitTester(workerModule, null)

        val r1 = ev.evaluator.evaluate(Agg(workerModule.all))
        assert(r1.failing.keyCount == 0)
        assert(workers.size == 5)

        val r2 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator)))
        assert(r2.failing.keyCount == 0)
        assert(workers.isEmpty)
      }

      test("single-target") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        val ev = UnitTester(workerModule, null)

        val r1 = ev.evaluator.evaluate(Agg(workerModule.all))
        assert(r1.failing.keyCount == 0)
        assert(workers.size == 5)

        val r2 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator, "foo.theWorker")))
        assert(r2.failing.keyCount == 0)
        assert(workers.size == 4)

        val r3 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator, "bar.theWorker")))
        assert(r3.failing.keyCount == 0)
        assert(workers.size == 3)

        val r4 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator, "bazz[1].theWorker")))
        assert(r4.failing.keyCount == 0)
        assert(workers.size == 2)
      }

      test("single-target via rm") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        val ev = UnitTester(workerModule, null)

        ev.evaluator.evaluate(Agg(workerModule.foo.theWorker))
          .ensuring(_.failing.keyCount == 0)
        assert(workers.size == 1)

        val originalFooWorker = workers.head

        ev.evaluator.evaluate(Agg(workerModule.bar.theWorker))
          .ensuring(_.failing.keyCount == 0)
        assert(workers.size == 2)
        assert(workers.exists(_ eq originalFooWorker))

        val originalBarWorker = workers.filter(_ ne originalFooWorker).head

        ev.evaluator.evaluate(Agg(workerModule.foo.theWorker))
          .ensuring(_.failing.keyCount == 0)
        assert(workers.size == 2)
        assert(workers.exists(_ eq originalFooWorker))

        ev.evaluator.evaluate(Agg(workerModule.bar.theWorker))
          .ensuring(_.failing.keyCount == 0)
        assert(workers.size == 2)
        assert(workers.exists(_ eq originalBarWorker))

        val outDir = os.Path(OutFiles.out, workerModule.millSourcePath)

        assert(!originalFooWorker.closed)
        os.remove(outDir / "foo/theWorker.json")

        ev.evaluator.evaluate(Agg(workerModule.foo.theWorker))
          .ensuring(_.failing.keyCount == 0)
        assert(workers.size == 2)
        assert(!workers.exists(_ eq originalFooWorker))
        assert(originalFooWorker.closed)

        assert(!originalBarWorker.closed)
        os.remove(outDir / "bar/theWorker.json")

        ev.evaluator.evaluate(Agg(workerModule.bar.theWorker))
          .ensuring(_.failing.keyCount == 0)
        assert(workers.size == 2)
        assert(!workers.exists(_ eq originalBarWorker))
        assert(originalBarWorker.closed)
      }

      test("single-module") {
        val workers = new mutable.HashSet[TestWorker]
        val workerModule = new WorkerModule(workers)
        val ev = UnitTester(workerModule, null)

        val r1 = ev.evaluator.evaluate(Agg(workerModule.all))
        assert(r1.failing.keyCount == 0)
        assert(workers.size == 5)

        val r2 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator, "foo")))
        assert(r2.failing.keyCount == 0)
        assert(workers.size == 4)

        val r3 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator, "bar")))
        assert(r3.failing.keyCount == 0)
        assert(workers.size == 3)

        val r4 = ev.evaluator.evaluate(Agg(workerModule.clean(ev.evaluator, "bazz[1]")))
        assert(r4.failing.keyCount == 0)
        assert(workers.size == 2)
      }
    }
  }
}
