package mill.define

import utest._
import mill.{T, Module}
import mill.util.TestUtil
import mill.testkit.TestBaseModule
object MacroErrorTests extends TestSuite {

  val tests = Tests {

    test("errors") {
      val expectedMsg =
        "Task{} members must be defs defined in a Cacher class/trait/object body"

      val err = compileError("object Foo extends TestBaseModule{ val x = Task {1} }")
      assert(err.msg == expectedMsg)
    }

    test("badParameterSets") {
      test("command") {
        val e = compileError("""
          object foo extends TestBaseModule{
            def w = Task.Command{1}
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
        val e = compileError("""
          val a = Task { 1 }
          val arr = Array(a)
          val b = {
            val c = 0
            Task{
              arr(c)()
            }
          }
        """)
        assert(e.msg.contains(
          "Modules and Tasks can only be defined within a mill Module"
        ))
      }
      test("neg") {

        val expectedMsg =
          "Target#apply() call cannot use `val n` defined within the Task{...} block"
        val err = compileError("""
          given mill.define.Ctx = ???
          new Module{
            def a = Task { 1 }
            val arr = Array(a)
            def b = {
              Task{
                val n = 0
                arr(n)()
              }
            }
          }
        """)
        assert(err.msg == expectedMsg)
      }
      test("neg2") {

        val expectedMsg =
          "Target#apply() call cannot use `val x` defined within the Task{...} block"
        val err = compileError("""
          given mill.define.Ctx = ???
          new Module{
            def a = Task { 1 }
            val arr = Array(a)
            def b = {
              Task{
                arr.map{x => x()}
              }
            }
          }
        """)
        assert(err.msg == expectedMsg)
      }
      test("neg3") {
        val borkedCachedDiamond1 = utest.compileError("""
          object borkedCachedDiamond1 {
            def up = Task { TestUtil.test() }
            def left = Task { TestUtil.test(up) }
            def right = Task { TestUtil.test(up) }
            def down = Task { TestUtil.test(left, right) }
          }
        """)
        assert(borkedCachedDiamond1.msg.contains(
          "Modules and Tasks can only be defined within a mill Module"
        ))
      }
    }

    test("badCrossKeys") {
      val error = utest.compileError(
        """
        object foo extends TestBaseModule{
          object cross extends Cross[MyCrossModule](Seq(1, 2, 3))
          trait MyCrossModule extends Cross.Module[String]
        }
      """
      )
      assert(error.msg.contains("type mismatch;"))
      assert(error.msg.contains("found   : Int"))
      assert(error.msg.contains("required: String"))
    }

    test("invalidCrossType") {
      val error = utest.compileError(
        """
        object foo extends TestBaseModule{
          object cross extends Cross[MyCrossModule](null.asInstanceOf[sun.misc.Unsafe])
          trait MyCrossModule extends Cross.Module[sun.misc.Unsafe]
        }
      """
      )
      assert(error.msg.contains(
        "Could not summon ToSegments[segArg.type]"
      ))
    }
  }
}
