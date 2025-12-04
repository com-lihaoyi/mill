package mill.resolve

import mill.api.{Discover, ModuleRef}
import mill.testkit.TestRootModule
import mainargs.arg
import mill.{Cross, Module, Task}
import utest.*
import mill.api.Result

object ErrorTests extends TestSuite {
  // Wrapper class so that module initialization errors are not fatal
  class ErrorGraphs {
    object moduleInitError extends TestRootModule {
      def rootTask = Task { println("Running rootTask"); "rootTask Result" }
      def rootCommand(@arg(positional = true) s: String) =
        Task.Command { println(s"Running rootCommand $s") }

      object foo extends Module {
        def fooTask = Task { println(s"Running fooTask"); 123 }
        def fooCommand(@arg(positional = true) s: String) =
          Task.Command { println(s"Running fooCommand $s") }
        throw Exception("Foo Boom")
      }

      object bar extends Module {
        def barTask = Task { println(s"Running barTask"); "barTask Result" }
        def barCommand(@arg(positional = true) s: String) =
          Task.Command { println(s"Running barCommand $s") }

        object qux extends Module {
          def quxTask = Task { println(s"Running quxTask"); "quxTask Result" }
          def quxCommand(@arg(positional = true) s: String) =
            Task.Command { println(s"Running quxCommand $s") }
          throw Exception("Qux Boom")
        }
      }

      lazy val millDiscover = Discover[this.type]
    }

    object moduleDependencyInitError extends TestRootModule {

      object foo extends Module {
        def fooTask = Task { println(s"Running fooTask"); 123 }
        def fooCommand(@arg(positional = true) s: String) =
          Task.Command { println(s"Running fooCommand $s") }
        throw Exception("Foo Boom")
      }

      object bar extends Module {
        def barTask = Task {
          println(s"Running barTask")
          s"${foo.fooTask()} barTask Result"
        }
        def barCommand(@arg(positional = true) s: String) = Task.Command {
          foo.fooCommand(s)()
          println(s"Running barCommand $s")
        }
      }

      lazy val millDiscover = Discover[this.type]
    }

    object crossModuleSimpleInitError extends TestRootModule {
      object myCross extends Cross[MyCross](1, 2, 3, 4) {
        throw Exception(s"MyCross Boom")
      }
      trait MyCross extends Cross.Module[Int] {
        def foo = Task { crossValue }
      }

      lazy val millDiscover = Discover[this.type]
    }
    object crossModulePartialInitError extends TestRootModule {
      object myCross extends Cross[MyCross](1, 2, 3, 4)
      trait MyCross extends Cross.Module[Int] {
        if (crossValue > 2) throw Exception(s"MyCross Boom $crossValue")
        def foo = Task { crossValue }
      }

      lazy val millDiscover = Discover[this.type]
    }
    object crossModuleSelfInitError extends TestRootModule {
      object myCross extends Cross[MyCross](1, 2, 3, throw Exception(s"MyCross Boom"))
      trait MyCross extends Cross.Module[Int] {
        def foo = Task { crossValue }
      }

      lazy val millDiscover = Discover[this.type]
    }

    object crossModuleParentInitError extends TestRootModule {
      object parent extends Module {
        throw Exception(s"Parent Boom")
        object myCross extends Cross[MyCross](1, 2, 3, 4)
        trait MyCross extends Cross.Module[Int] {
          def foo = Task { crossValue }
        }
      }

      lazy val millDiscover = Discover[this.type]
    }

    // The module names repeat, but it's not actually cyclic and is meant to confuse the cycle detection.
    object NonCyclicModules extends TestRootModule {
      def foo = Task { "foo" }

      object A extends Module {
        def b = B
      }
      object B extends Module {
        object A extends Module {
          def b = B
        }
        def a = A

        object B extends Module {
          object B extends Module {}
          object A extends Module {
            def b = B
          }
          def a = A
        }
      }

      lazy val millDiscover = Discover[this.type]
    }

    object CyclicModuleRefInitError extends TestRootModule {
      def foo = Task { "foo" }

      // See issue: https://github.com/com-lihaoyi/mill/issues/3715
      trait CommonModule extends Module {
        def foo = Task { "foo" }
        def moduleDeps: Seq[CommonModule] = Seq()
        def a = myA
        def b = myB
      }

      object myA extends A
      trait A extends CommonModule
      object myB extends B
      trait B extends CommonModule {
        override def moduleDeps = super.moduleDeps ++ Seq(a)
      }
      lazy val millDiscover = Discover[this.type]
    }

    object CyclicModuleRefInitError2 extends TestRootModule {
      // The cycle is in the child
      def A = CyclicModuleRefInitError
      lazy val millDiscover = Discover[this.type]
    }

    object CyclicModuleRefInitError3 extends TestRootModule {
      // The cycle is in directly here
      object A extends Module {
        def b = B
      }
      object B extends Module {
        def a = A
      }
      lazy val millDiscover = Discover[this.type]
    }

    object CrossedCyclicModuleRefInitError extends TestRootModule {
      object cross extends mill.Cross[Cross]("210", "211", "212")
      trait Cross extends Cross.Module[String] {
        def suffix = Task { crossValue }
        def c2 = cross2
      }

      object cross2 extends mill.Cross[Cross2]("210", "211", "212")
      trait Cross2 extends Cross.Module[String] {
        override def moduleDir = super.moduleDir / crossValue
        def suffix = Task { crossValue }
        def c1 = cross
      }
      lazy val millDiscover = Discover[this.type]
    }

    // This edge case shouldn't be an error
    object ModuleRefWithNonModuleRefChild extends TestRootModule {
      def foo = Task { "foo" }

      def aRef = A
      def a = ModuleRef(A)

      object A extends Module {}

      lazy val millDiscover = Discover[this.type]
    }

    object ModuleRefCycle extends TestRootModule {
      def foo = Task { "foo" }

      // The cycle is in directly here
      object A extends Module {
        def b = ModuleRef(B)
      }
      object B extends Module {
        def a = ModuleRef(A)
      }

      lazy val millDiscover = Discover[this.type]
    }
  }

  def isShortError(x: Result[?], s: String) = {
    x match {
      case f: Result.Failure =>
        val str = mill.internal.Util.formatError(f, s => s)

        str.contains(s) &&
        // Make sure the stack traces are truncated and short-ish, and do not
        // contain the entire Mill internal call stack at point of failure
        str.linesIterator.size < 40
      case _ => false
    }

  }

  val tests = Tests {
    val errorGraphs = ErrorGraphs()
    import errorGraphs.*

    test("moduleInitError") {
      test("simple") {
        val check = Checker(moduleInitError)
        // We can resolve the root module tasks even when the
        // sub-modules fail to initialize
        test("rootTask") - check.checkSeq(
          Seq("rootTask"),
          Result.Success(Set(_.rootTask)),
          // Even though instantiating the task fails due to the module
          // failing, we can still resolve the task name, since resolving tasks
          // does not require instantiating the module
          Set("rootTask")
        )
        test("rootCommand") - check.checkSeq(
          Seq("rootCommand", "hello"),
          Result.Success(Set(_.rootCommand("hello"))),
          Set("rootCommand")
        )

        // Resolving tasks on a module that fails to initialize is properly
        // caught and reported in the Either result
        test("fooTask") - check.checkSeq0(
          Seq("foo.fooTask"),
          isShortError(_, "Foo Boom"),
          _ == Result.Success(List("foo.fooTask"))
        )
        test("fooCommand") - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Result.Success(List("foo.fooCommand"))
        )

        // Sub-modules that can initialize allow tasks to be resolved, even
        // if their siblings or children are broken
        test("barTask") - check.checkSeq(
          Seq("bar.barTask"),
          Result.Success(Set(_.bar.barTask)),
          Set("bar.barTask")
        )
        test("barCommand") - check.checkSeq(
          Seq("bar.barCommand", "hello"),
          Result.Success(Set(_.bar.barCommand("hello"))),
          Set("bar.barCommand")
        )

        // Nested sub-modules that fail to initialize are properly handled
        test("quxTask") - check.checkSeq0(
          Seq("bar.qux.quxTask"),
          isShortError(_, "Qux Boom"),
          _ == Result.Success(List("bar.qux.quxTask"))
        )
        test("quxCommand") - check.checkSeq0(
          Seq("bar.qux.quxCommand", "hello"),
          isShortError(_, "Qux Boom"),
          _ == Result.Success(List("bar.qux.quxCommand"))
        )
      }

      test("dependency") {
        val check = Checker(moduleDependencyInitError)

        test("fooTask") - check.checkSeq0(
          Seq("foo.fooTask"),
          isShortError(_, "Foo Boom"),
          _ == Result.Success(List("foo.fooTask"))
        )
        test("fooCommand") - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Result.Success(List("foo.fooCommand"))
        )
        // Even though the `bar` module doesn't throw, `barTask` and
        // `barCommand` depend on the `fooTask` and `fooCommand` tasks on the
        // `foo` module, and the `foo` module blows up. This should turn up as
        // a stack trace when we try to resolve bar
        test("barTask") - check.checkSeq0(
          Seq("bar.barTask"),
          isShortError(_, "Foo Boom"),
          _ == Result.Success(List("bar.barTask"))
        )
        test("barCommand") - check.checkSeq0(
          Seq("bar.barCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Result.Success(List("bar.barCommand"))
        )
      }

      test("cross") {

        test("simple") {
          val check = Checker(crossModuleSimpleInitError)
          check.checkSeq0(
            Seq("myCross[1].foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
          check.checkSeq0(
            Seq("__.foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
          check.checkSeq0(
            Seq("__"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
        }
        test("partial") {
          val check = Checker(crossModulePartialInitError)

          // If any one of the cross modules fails to initialize, even if it's
          // not the one you are asking for, we fail and make sure to catch and
          // handle the error
          test - check.checkSeq(
            Seq("myCross[1].foo"),
            Result.Success(Set(_.myCross(1).foo)),
            Set("myCross[1].foo")
          )
          test - check.checkSeq0(
            Seq("myCross[3].foo"),
            isShortError(_, "MyCross Boom 3"),
            isShortError(_, "MyCross Boom 3")
          )
          // Using wildcards forces evaluation of the myCross submodules,
          // causing failure if we try to instantiate the tasks, but just
          // resolving the tasks is fine since we don't need to instantiate
          // the sub-modules to resolve their paths
          test - check.checkSeq0(
            Seq("myCross._.foo"),
            s => isShortError(s, "MyCross Boom 3") && isShortError(s, "MyCross Boom 4"),
            _ == Result.Success(List(
              "myCross[1].foo",
              "myCross[2].foo",
              "myCross[3].foo",
              "myCross[4].foo"
            ))
          )
          test - check.checkSeq0(
            Seq("myCross[_].foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
          test - check.checkSeq0(
            Seq("__.foo"),
            s => isShortError(s, "MyCross Boom 3") && isShortError(s, "MyCross Boom 4"),
            _ == Result.Success(List(
              "myCross[1].foo",
              "myCross[2].foo",
              "myCross[3].foo",
              "myCross[4].foo"
            ))
          )
          test - check.checkSeq0(
            Seq("__"),
            s => isShortError(s, "MyCross Boom 3") && isShortError(s, "MyCross Boom 4"),
            _ == Result.Success(List(
              "",
              "myCross",
              "myCross[1]",
              "myCross[2]",
              "myCross[3]",
              "myCross[4]",
              "myCross[1].foo",
              "myCross[2].foo",
              "myCross[3].foo",
              "myCross[4].foo"
            ))
          )
        }
        test("self") {
          val check = Checker(crossModuleSelfInitError)

          // When the cross module itself fails to initialize, even before its
          // children get a chance to init. Instantiating the tasks fails, but
          // resolving the task paths fails too, since finding the valid cross
          // values requires instantiating the cross module. Make sure both
          // fail as expected, and that their exception is properly wrapped.
          test - check.checkSeq0(
            Seq("myCross[3].foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )

          test - check.checkSeq0(
            Seq("myCross._.foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
        }

        test("parent") {
          val check = Checker(crossModuleParentInitError)

          // When the parent of the cross module fails to initialize, even
          // before the cross module or its children get a chance to init,
          // ensure we handle the error properly
          test - check.checkSeq0(
            Seq("parent.myCross[3].foo"),
            isShortError(_, "Parent Boom"),
            isShortError(_, "Parent Boom")
          )

          test - check.checkSeq0(
            Seq("parent.myCross._.foo"),
            isShortError(_, "Parent Boom"),
            isShortError(_, "Parent Boom")
          )
        }
      }
    }
    test("cyclicModuleRefInitError") {
      val check = Checker(CyclicModuleRefInitError)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at myA.a,")
      )
      test - check(
        "_",
        Result.Success(Set(_.foo))
      )
      test - check.checkSeq0(
        Seq("myA.__"),
        isShortError(_, "Cyclic module reference detected at myA.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a.__"),
        isShortError(_, "Cyclic module reference detected at myA.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a._"),
        isShortError(_, "Cyclic module reference detected at myA.a.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a._.a"),
        isShortError(_, "Cyclic module reference detected at myA.a.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a.b.a"),
        isShortError(_, "Cyclic module reference detected at myA.a.b.a,")
      )
    }
    test("cyclicModuleRefInitError2") {
      val check = Checker(CyclicModuleRefInitError2)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at A.myA.a,")
      )
    }
    test("cyclicModuleRefInitError3") {
      val check = Checker(CyclicModuleRefInitError3)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at A.b.a,")
      )
      test - check.checkSeq0(
        Seq("A.__"),
        isShortError(_, "Cyclic module reference detected at A.b.a,")
      )
      test - check.checkSeq0(
        Seq("A.b.__.a.b"),
        isShortError(_, "Cyclic module reference detected at A.b.a,")
      )
    }
    test("crossedCyclicModuleRefInitError") {
      val check = Checker(CrossedCyclicModuleRefInitError)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at cross[210].c2[210].c1,")
      )
    }
    test("nonCyclicModules") {
      val check = Checker(NonCyclicModules)
      test - check(
        "__",
        Result.Success(Set(_.foo))
      )
    }
    test("moduleRefWithNonModuleRefChild") {
      val check = Checker(ModuleRefWithNonModuleRefChild)
      test - check(
        "__",
        Result.Success(Set(_.foo))
      )
    }
    test("moduleRefCycle") {
      val check = Checker(ModuleRefCycle)
      test - check(
        "__",
        Result.Success(Set(_.foo))
      )
      test - check(
        "__._",
        Result.Success(Set(_.foo))
      )
    }
  }
}
