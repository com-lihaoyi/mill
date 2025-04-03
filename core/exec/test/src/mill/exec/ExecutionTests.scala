package mill.exec

import mill.define.{Discover, TargetImpl, Task}
import mill.util.TestGraphs
import mill.testkit.{TestBaseModule, UnitTester}
import mill.{PathRef, exec}
import utest.*

object ExecutionTests extends TestSuite {
  object traverseBuild extends TestBaseModule {
    trait TaskModule extends mill.Module {
      def x = 1
      def task = Task { x }
    }
    object mod1 extends TaskModule {
      def x = 1
    }
    object mod2 extends TaskModule {
      def x = 10
    }
    object mod3 extends TaskModule {
      def x = 100
    }

    def task4 = Task.traverse(Seq(mod1, mod2, mod3))(_.task)
    lazy val millDiscover = Discover[this.type]
  }

  object anonTaskFailure extends TestBaseModule {
    def anon = Task.Anon[Int] { throw new Exception("boom") }

    def task = Task[Int] { anon() }
    lazy val millDiscover = Discover[this.type]
  }
  class Checker[T <: mill.testkit.TestBaseModule](module: T)
      extends exec.Checker(module)

  val tests = Tests {
    import TestGraphs._
    import utest._
    test("single") {
      val checker = new Checker(singleton)
      checker(singleton.single, 123, Seq(singleton.single), extraEvaled = -1)
      checker(singleton.single, 123, Seq(), extraEvaled = -1)
    }

    test("source") {
      object build extends TestBaseModule {
        def source = Task.Source { "hello/world.txt" }
        def task = Task { os.read(source().path) + " !" }
        lazy val millDiscover = Discover[this.type]
      }

      val checker = new Checker(build)

      os.write(build.moduleDir / "hello/world.txt", "i am cow", createFolders = true)
      checker(
        build.task,
        "i am cow !",
        Seq(build.source, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )
      checker(build.task, "i am cow !", Seq(build.source), extraEvaled = -1, secondRunNoOp = false)
      os.write.over(build.moduleDir / "hello/world.txt", "hear me moo")

      checker(
        build.task,
        "hear me moo !",
        Seq(build.source, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )
      checker(
        build.task,
        "hear me moo !",
        Seq(build.source),
        extraEvaled = -1,
        secondRunNoOp = false
      )
    }
    test("sources") {
      object build extends TestBaseModule {
        def source = Task.Sources("hello/world.txt", "hello/world2.txt")
        def task = Task { source().map(pr => os.read(pr.path)).mkString + "!" }
        lazy val millDiscover = Discover[this.type]
      }

      val checker = new Checker(build)

      os.write(build.moduleDir / "hello/world.txt", "i am cow ", createFolders = true)
      os.write(build.moduleDir / "hello/world2.txt", "hear me moo ", createFolders = true)
      checker(
        build.task,
        "i am cow hear me moo !",
        Seq(build.source, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )
      checker(
        build.task,
        "i am cow hear me moo !",
        Seq(build.source),
        extraEvaled = -1,
        secondRunNoOp = false
      )
      os.write.over(build.moduleDir / "hello/world.txt", "I AM COW ")

      checker(
        build.task,
        "I AM COW hear me moo !",
        Seq(build.source, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )

      os.write.over(build.moduleDir / "hello/world2.txt", "HEAR ME MOO ")
      checker(
        build.task,
        "I AM COW HEAR ME MOO !",
        Seq(build.source),
        extraEvaled = -1,
        secondRunNoOp = false
      )
    }

    test("input") {
      var x = 10
      object build extends TestBaseModule {
        def input = Task.Input { x }
        def task = Task { input() + 1 }
        lazy val millDiscover = Discover[this.type]
      }

      val checker = new Checker(build)
      checker(build.task, 11, Seq(build.input, build.task), extraEvaled = -1, secondRunNoOp = false)
      checker(build.task, 11, Seq(build.input), extraEvaled = -1, secondRunNoOp = false)

      x = 100

      checker(
        build.task,
        101,
        Seq(build.input, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )
      checker(build.task, 101, Seq(build.input), extraEvaled = -1, secondRunNoOp = false)
    }

    test("dest") {
      var x = 10
      object build extends TestBaseModule {
        def input = Task.Input { x }
        def task = Task {
          assert(!os.exists(Task.dest / "file.txt"))
          os.write(Task.dest / "file.txt", "hello" + input())
          PathRef(Task.dest / "file.txt")
        }
        def task2 = Task { os.read(task().path) }
        lazy val millDiscover = Discover[this.type]
      }

      val checker = new Checker(build)
      checker(
        build.task2,
        "hello10",
        Seq(build.input, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )

      x = 11
      checker(
        build.task2,
        "hello11",
        Seq(build.input, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )
    }

    test("persistent") {
      var x = 10
      object build extends TestBaseModule {
        def input = Task.Input { x }
        def task = Task(persistent = true) {
          val file = Task.dest / "file.txt"

          if (os.exists(file)) os.write.over(file, os.read(file) + "hello" + input())
          else os.write(file, "hello" + input())

          PathRef(file)
        }
        def task2 = Task { os.read(task().path) }
        lazy val millDiscover = Discover[this.type]
      }

      val checker = new Checker(build)
      checker(
        build.task2,
        "hello10",
        Seq(build.input, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )

      x = 11
      checker(
        build.task2,
        "hello10hello11",
        Seq(build.input, build.task),
        extraEvaled = -1,
        secondRunNoOp = false
      )
    }

    test("worker") {
      var x = 10
      class MyWorker(val n: Int) extends AutoCloseable {
        def close() = closed = true
        var closed = false
      }

      object build extends TestBaseModule {
        def input = Task.Input { x }
        def worker = Task.Worker { new MyWorker(input()) }
        lazy val millDiscover = Discover[this.type]
      }

      UnitTester(build, null).scoped { tester =>
        val Right(UnitTester.Result(worker1, _)) = tester.apply(build.worker)
        val Right(UnitTester.Result(worker2, _)) = tester.apply(build.worker)
        assert(worker1 == worker2)
        assert(worker1.n == 10)
        assert(!worker1.closed)
        x = 11
        val Right(UnitTester.Result(worker3, _)) = tester.apply(build.worker)
        assert(worker3 != worker2)
        assert(worker3.n == 11)
        assert(!worker3.closed)
        assert(worker1.closed)
      }
    }

    test("command") {
      var x = 10
      var y = 0
      object build extends TestBaseModule {
        def input = Task.Input { x }
        def command(n: Int) = Task.Command { y += input() + n }
        lazy val millDiscover = Discover[this.type]
      }

      UnitTester(build, null).scoped { tester =>
        assert(y == 0)
        tester.apply(build.command(0))
        assert(y == 10)
        tester.apply(build.command(1))
        assert(y == 21)
        x = 5
        tester.apply(build.command(2))
        assert(y == 28)

      }
    }

    test("anon") {
      var x = 10
      var y = 0
      object build extends TestBaseModule {
        def input = Task.Input { x }
        def anon = Task.Anon { y += input() }
        lazy val millDiscover = Discover[this.type]
      }

      UnitTester(build, null).scoped { tester =>
        assert(y == 0)
        tester.apply(build.anon)
        assert(y == 10)
        tester.apply(build.anon)
        assert(y == 20)
        x = 5
        tester.apply(build.anon)
        assert(y == 25)

      }
    }

    test("error") {
      var x = 10
      var y = 0
      object build extends TestBaseModule {
        def input = Task.Input { x }
        def task = Task { y += 100 / input() }
        lazy val millDiscover = Discover[this.type]
      }

      UnitTester(build, null).scoped { tester =>
        assert(y == 0)
        val Right(_) = tester.apply(build.task)
        assert(y == 10)
        x = 0
        val Left(_) = tester.apply(build.task)
        assert(y == 10)
      }
    }

    test("sequence") {
      object build extends TestBaseModule {
        def task1 = Task { 1 }
        def task2 = Task { 10 }
        def task3 = Task { 100 }
        def task4 = Task.sequence(Seq(task1, task2, task3))
        lazy val millDiscover = Discover[this.type]
      }
      UnitTester(build, null).scoped { tester =>
        val Right(UnitTester.Result(Seq(1, 10, 100), _)) = tester.apply(build.task4)
      }
    }
    test("traverse") {
      UnitTester(traverseBuild, null).scoped { tester =>
        val Right(UnitTester.Result(Seq(1, 10, 100), _)) = tester.apply(traverseBuild.task4)
      }
    }

    test("zip") {
      object build extends TestBaseModule {
        def task1 = Task { 1 }
        def task2 = Task { 10 }
        def task4 = task1.zip(task2)
        lazy val millDiscover = Discover[this.type]
      }
      UnitTester(build, null).scoped { tester =>
        val Right(UnitTester.Result((1, 10), _)) = tester.apply(build.task4)
      }
    }

    test("map") {
      object build extends TestBaseModule {
        def task1 = Task { 1 }
        def task2 = task1.map(_ + 10)

        lazy val millDiscover = Discover[this.type]
      }
      UnitTester(build, null).scoped { tester =>
        val Right(UnitTester.Result(11, _)) = tester.apply(build.task2)
      }
    }

    test("triangleTask") {

      import triangleTask._
      val checker = new Checker(triangleTask)
      checker(right, 3, Seq(left, right), extraEvaled = -1)
      checker(left, 1, Seq(), extraEvaled = -1)

    }
    test("multiTerminalGroup") {
      import multiTerminalGroup._

      val checker = new Checker(multiTerminalGroup)
      checker(right, 1, Seq(right), extraEvaled = -1)
      checker(left, 1, Seq(left), extraEvaled = -1)
    }

    test("multiTerminalBoundary") {

      import multiTerminalBoundary._

      val checker = new Checker(multiTerminalBoundary)
      checker(task2, 4, Seq(right, left), extraEvaled = -1, secondRunNoOp = false)
      checker(task2, 4, Seq(), extraEvaled = -1, secondRunNoOp = false)
    }

    test("nullTasks") {

      object nullTasks extends TestBaseModule {
        val nullString: String = null
        def nullTask1 = Task.Anon { nullString }
        def nullTask2 = Task.Anon { nullTask1() }

        def nullTarget1 = Task { nullString }
        def nullTarget2 = Task { nullTarget1() }
        def nullTarget3 = Task { nullTask1() }
        def nullTarget4 = Task { nullTask2() }

        def nullCommand1() = Task.Command { nullString }
        def nullCommand2() = Task.Command { nullTarget1() }
        def nullCommand3() = Task.Command { nullTask1() }
        def nullCommand4() = Task.Command { nullTask2() }

        lazy val millDiscover = Discover[this.type]
      }

      import nullTasks._
      val checker = new Checker(nullTasks)
      checker(nullTarget1, null, Seq(nullTarget1), extraEvaled = -1)
      checker(nullTarget1, null, Seq(), extraEvaled = -1)
      checker(nullTarget2, null, Seq(nullTarget2), extraEvaled = -1)
      checker(nullTarget2, null, Seq(), extraEvaled = -1)
      checker(nullTarget3, null, Seq(nullTarget3), extraEvaled = -1)
      checker(nullTarget3, null, Seq(), extraEvaled = -1)
      checker(nullTarget4, null, Seq(nullTarget4), extraEvaled = -1)
      checker(nullTarget4, null, Seq(), extraEvaled = -1)

      val nc1 = nullCommand1()
      val nc2 = nullCommand2()
      val nc3 = nullCommand3()
      val nc4 = nullCommand4()

      checker(nc1, null, Seq(nc1), extraEvaled = -1, secondRunNoOp = false)
      checker(nc1, null, Seq(nc1), extraEvaled = -1, secondRunNoOp = false)
      checker(nc2, null, Seq(nc2), extraEvaled = -1, secondRunNoOp = false)
      checker(nc2, null, Seq(nc2), extraEvaled = -1, secondRunNoOp = false)
      checker(nc3, null, Seq(nc3), extraEvaled = -1, secondRunNoOp = false)
      checker(nc3, null, Seq(nc3), extraEvaled = -1, secondRunNoOp = false)
      checker(nc4, null, Seq(nc4), extraEvaled = -1, secondRunNoOp = false)
      checker(nc4, null, Seq(nc4), extraEvaled = -1, secondRunNoOp = false)
    }

    test("backticked") {
      UnitTester(bactickIdentifiers, null).scoped { tester =>
        val Right(UnitTester.Result(1, _)) = tester.apply(bactickIdentifiers.`up-target`)
        val Right(UnitTester.Result(3, _)) = tester.apply(bactickIdentifiers.`a-down-target`)
        val Right(UnitTester.Result(3, _)) = tester.apply(bactickIdentifiers.`invisible&`)
        val Right(UnitTester.Result(4, _)) =
          tester.apply(bactickIdentifiers.`nested-module`.`nested-target`)
      }
    }
    test("anonTaskFailure") {
      UnitTester(anonTaskFailure, null).scoped { tester =>
        val res = tester.evaluator.execute(Seq(anonTaskFailure.task))
        assert(res.executionResults.transitiveFailing.keySet == Set(anonTaskFailure.task))
      }
    }
  }
}
