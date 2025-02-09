package mill.define

import utest._
import mill.testkit.TestBaseModule
object MacroErrorTests extends TestSuite {

  val tests = Tests {

    test("errors") {
      val expectedMsg =
        "Task{} members must be defs defined in a Module class/trait/object body"

      val err = compileError("object Foo extends TestBaseModule{ val x = Task {1} }")
      assert(err.msg == expectedMsg)
    }

    test("badParameterSets") {
      test("command") {
        val e = compileError("""
          object foo extends TestBaseModule{
            def w = Task.Command{1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.define.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task.Command` definitions must have 1 parameter list"),
          e.pos.contains("def w = ")
        )
      }

      test("target") {
        val e = compileError("""
          object foo extends TestBaseModule{
            def x() = Task {1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.define.Discover[foo.type]
        """)
        assert(
          e.msg.contains("Target definitions must have 0 parameter lists"),
          e.pos.contains("def x() = ")
        )
      }
      test("input") {
        val e = compileError("""
          object foo extends TestBaseModule{
            def y() = Task.Input{1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.define.Discover[foo.type]
        """)
        assert(
          e.msg.contains("Target definitions must have 0 parameter lists"),
          e.pos.contains("def y() = ")
        )
      }
      test("sources") {
        val e = compileError("""
          object foo extends TestBaseModule{
            def z() = Task.Sources{os.pwd}
            lazy val millDiscover = Discover[this.type]
          }
          mill.define.Discover[foo.type]
        """)
        assert(
          e.msg.contains("Target definitions must have 0 parameter lists"),
          e.pos.contains("def z() = ")
        )
      }
      test("persistent") {
        val e = compileError("""
          object foo extends TestBaseModule{
            def a() = Task(persistent = true){1}
            lazy val millDiscover = Discover[this.type]
          }
          mill.define.Discover[foo.type]
        """)
        assert(
          e.msg.contains("Target definitions must have 0 parameter lists"),
          e.pos.contains("def a() = ")
        )
      }
    }
    test("badTmacro") {
      // Make sure we can reference values from outside the Task{...} block as part
      // of our `Target#apply()` calls, but we cannot reference any values that
      // come from inside the Task{...} block
      test("pos") {
        // This should compile
        object foo extends TestBaseModule {
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
        val e = compileError("""def a = Task { 1 }""")
        assert(e.msg.contains(
          "Task{} members must be defs defined in a Module class/trait/object body"
        ))
      }

      test("neg2") {
        val e = compileError("object foo extends TestBaseModule{ val a = Task { 1 } }")
        assert(e.msg.contains(
          "Task{} members must be defs defined in a Module class/trait/object body"
        ))
      }
      test("neg3") {

        val expectedMsg =
          "Target#apply() call cannot use `val n` defined within the Task{...} block"
        val err = compileError("""
          object foo extends TestBaseModule{
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
          "Target#apply() call cannot use `val x` defined within the Task{...} block"
        val err = compileError("""
          object foo extends TestBaseModule{
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
//      test("neg5") {
//        val borkedCachedDiamond1 = utest.compileError("""
//          object borkedCachedDiamond1 {
//            def up = Task { TestUtil.test() }
//            def left = Task { TestUtil.test(up) }
//            def right = Task { TestUtil.test(up) }
//            def down = Task { TestUtil.test(left, right) }
//          }
//        """)
//        assert(borkedCachedDiamond1.msg.contains(
//          "Task{} members must be defs defined in a Module class/trait/object body"
//        ))
//      }
    }

    test("badCrossKeys") {
      val error = utest.compileError(
        """
        object foo extends TestBaseModule{
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
      val error = utest.compileError(
        """
        object foo extends TestBaseModule{
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
      val error = utest.compileError(
        """
        object foo extends TestBaseModule{
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
  }
}
