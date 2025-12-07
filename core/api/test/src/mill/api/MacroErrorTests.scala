package mill.api

import utest.*
import mill.testkit.TestRootModule

import scala.annotation.unused

object MacroErrorTests extends TestSuite {

  val tests = Tests {

    test("errors") {
      val expectedMsg =
        "Task{} members must be defs defined in a Module class/trait/object body"

      val err = assertCompileError("object Foo extends TestRootModule{ val x = Task {1} }")
      assert(err.msg == expectedMsg)
    }

    test("badParameterSets") {
      test("command") {
        val e = assertCompileError("""
          object foo extends TestRootModule{
            def w = Task.Command{1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.api.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task.Command` definition `method w` must have 1 parameter list"),
          e.pos.contains("def w = ")
        )
      }

      test("task") {
        val e = assertCompileError("""
          object foo extends TestRootModule{
            def x() = Task {1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.api.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task` definition `method x` must have 0 parameter lists"),
          e.pos.contains("def x() = ")
        )
      }
      test("taskHiddenType") {
        // Make sure we raise an error even if the `T`/`Task.Simple` type is hidden
        // by a `Task[Int]` type ascription
        val e = assertCompileError("""
          object foo extends TestRootModule{
            def x(): Task[Int] = Task {1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.api.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task` definition `method x` must have 0 parameter lists"),
          e.pos.contains("def x():")
        )
      }

      test("input") {
        val e = assertCompileError("""
          object foo extends TestRootModule{
            def y() = Task.Input{1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.api.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task.Input` definition `method y` must have 0 parameter lists"),
          e.pos.contains("def y() = ")
        )
      }
      test("sources") {
        val e = assertCompileError("""
          object foo extends TestRootModule{
            def z() = Task.Sources{os.pwd}
            lazy val millDiscover = Discover[this.type]
          }
          mill.api.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task.Sources` definition `method z` must have 0 parameter lists"),
          e.pos.contains("def z() = ")
        )
      }
      test("persistent") {
        val e = assertCompileError("""
          object foo extends TestRootModule{
            def a() = Task(persistent = true){1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.api.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task` definition `method a` must have 0 parameter lists"),
          e.pos.contains("def a() = ")
        )
      }
    }
    test("badTmacro") {
      // Make sure we can reference values from outside the Task{...} block as part
      // of our `Task#apply()` calls, but we cannot reference any values that
      // come from inside the Task{...} block
      test("pos") {
        // This should compile
        @unused object foo extends TestRootModule {
          def a = Task { 1 }
          val arr = Array(a)
          def b = {
            val c = 0
            Task {
              arr(c)()
            }
          }
          lazy val millDiscover = Discover[this.type]
        }
      }
      test("neg1") {
        val e = assertCompileError("""def a = Task { 1 }""")
        assert(e.msg.contains(
          "Task{} members must be defs defined in a Module class/trait/object body"
        ))
      }

      test("neg2") {
        val e = assertCompileError("object foo extends TestRootModule{ val a = Task { 1 } }")
        assert(e.msg.contains(
          "Task{} members must be defs defined in a Module class/trait/object body"
        ))
      }
      test("neg3") {

        val expectedMsg =
          "Task#apply() call cannot use `val n` defined within the Task{...} block"
        val err = assertCompileError("""
          object foo extends TestRootModule{
            def a = Task { 1 }
            val arr = Array(a)
            def b = {
              Task{
                val n = 0
                arr(n)()
              }
            }
            lazy val millDiscover = Discover[this.type]
          }
        """)
        assert(err.msg == expectedMsg)
      }
      test("neg4") {

        val expectedMsg =
          "Task#apply() call cannot use `val x` defined within the Task{...} block"
        val err = assertCompileError("""
          object foo extends TestRootModule{
            def a = Task { 1 }
            val arr = Array(a)
            def b = {
              Task{
                arr.map{x => x()}
              }
            }
            lazy val millDiscover = Discover[this.type]
          }
        """)
        assert(err.msg == expectedMsg)
      }
    }

    test("badCrossKeys") {
      val error = utest.assertCompileError(
        """
        object foo extends TestRootModule{
          object cross extends Cross[MyCrossModule](Seq(1, 2, 3))
          trait MyCrossModule extends Cross.Module[String]
          lazy val millDiscover = Discover[this.type]
        }
      """
      )
      assert(error.msg.contains("Cannot convert value to Cross.Factory[MyCrossModule]:"))
      assert(error.msg.contains("- crossValue requires java.lang.String"))
      assert(error.msg.contains("  but inner element of type scala.Int did not match."))
    }

    test("badCrossKeys2") {
      val error = utest.assertCompileError(
        """
        object foo extends TestRootModule{
          object cross extends Cross[MyCrossModule](Seq((1, 2), (2, 2), (3, 3)))
          trait MyCrossModule extends Cross.Module2[String, Boolean]
          lazy val millDiscover = Discover[this.type]
        }
      """
      )
      assert(error.msg.contains("Cannot convert value to Cross.Factory[MyCrossModule]:"))
      assert(error.msg.contains("- crossValue requires java.lang.String"))
      assert(error.msg.contains(
        "  but inner element of type (scala.Int, scala.Int) did not match at index 0."
      ))
      assert(error.msg.contains("- crossValue2 requires scala.Boolean"))
      assert(error.msg.contains(
        "  but inner element of type (scala.Int, scala.Int) did not match at index 1."
      ))
    }

    test("invalidCrossType") {
      val error = utest.assertCompileError(
        """
        object foo extends TestRootModule{
          object cross extends Cross[MyCrossModule](null.asInstanceOf[sun.misc.Unsafe])
          trait MyCrossModule extends Cross.Module[sun.misc.Unsafe]
          lazy val millDiscover = Discover[this.type]
        }
      """
      )
      assert(error.msg.contains(
        "Could not summon ToSegments[sun.misc.Unsafe]"
      ))
    }
    test("taskWithinNormalObject") {
      test("task") {
        val error = utest.assertCompileError(
          """
          object foo extends TestRootModule {
            object bar {
              def w = Task{1}
            }

            lazy val millDiscover = Discover[this.type]
          }
          """
          )

          assert(error.msg.contains(
            "Task{} members must be defs defined in a Module class/trait/object body"
          ))
      }
      test("command") {
        val error = utest.assertCompileError(
          """
          object foo extends TestRootModule {
            object bar {
              def w() = Task.Command{1}
            }

            lazy val millDiscover = Discover[this.type]
          }
          """
          )

          assert(error.msg.contains(
            "Task{} members must be defs defined in a Module class/trait/object body"
          ))
      }
    }

    test("taskWithinATask") {
      val nestedTaskError = "A `Task[A]` cannot be a parameter of another `Task[A]`"

      test("simple") {
        val error = utest.assertCompileError(
          """
          object foo extends TestRootModule {
            def taskWithinTask = Task.Anon { Task.Anon { 42 } }

            lazy val millDiscover = Discover[this.type]
          }
          """
        )

        assert(error.msg.contains(nestedTaskError))
      }

      test("nested") {
        val error = utest.assertCompileError(
          """
          object foo extends TestRootModule {
            def taskWithinTask = Task.Anon { Seq(Task.Anon { 42 }) }

            lazy val millDiscover = Discover[this.type]
          }
          """
        )

        assert(error.msg.contains(nestedTaskError))
      }

      test("inAnotherType") {
        val error = utest.assertCompileError(
          """
          object foo extends TestRootModule {
            def taskWithinTask = Seq(Task.Anon { Task.Anon { 42 } })

            lazy val millDiscover = Discover[this.type]
          }
          """
        )

        assert(error.msg.contains(nestedTaskError))
      }
    }
  }
}
