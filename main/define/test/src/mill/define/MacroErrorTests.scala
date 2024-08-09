package mill.define

import utest._
import mill.{T, Task, Module}
import mill.util.TestUtil
object MacroErrorTests extends TestSuite {

  val tests = Tests {

    "errors" - {
      val expectedMsg =
        "T{} members must be defs defined in a Cacher class/trait/object body"

      val err = compileError("object Foo extends TestUtil.BaseModule{ val x = Task {1} }")
      assert(err.msg == expectedMsg)
    }

    "badParameterSets" - {
      "command" - {
        val e = compileError("""
          object foo extends mill.util.TestUtil.BaseModule{
            def w = Task.command{1}
          }
          mill.define.Discover[foo.type]
        """)
        assert(
          e.msg.contains("`Task.command` definitions must have 1 parameter list"),
          e.pos.contains("def w = ")
        )
      }

//      "target" - {
////        val e = compileError("""
//          object foo extends mill.util.TestUtil.BaseModule{
//            def x() = Task {1}
//          }
//          mill.define.Discover[foo.type]
////        """)
////        assert(
////          e.msg.contains("Target definitions must have 0 parameter lists"),
////          e.pos.contains("def x() = ")
////        )
//      }
//      "input" - {
//        val e = compileError("""
//          object foo extends mill.util.TestUtil.BaseModule{
//            def y() = Task.input{1}
//          }
//          mill.define.Discover[foo.type]
//        """)
//        assert(
//          e.msg.contains("Target definitions must have 0 parameter lists"),
//          e.pos.contains("def y() = ")
//        )
//      }
//      "sources" - {
//        val e = compileError("""
//          object foo extends mill.util.TestUtil.BaseModule{
//            def z() = Task.sources{os.pwd}
//          }
//          mill.define.Discover[foo.type]
//        """)
//        assert(
//          e.msg.contains("Target definitions must have 0 parameter lists"),
//          e.pos.contains("def z() = ")
//        )
//      }
//      "persistent" - {
//        val e = compileError("""
//          object foo extends mill.util.TestUtil.BaseModule{
//            def a() = Task.persistent{1}
//          }
//          mill.define.Discover[foo.type]
//        """)
//        assert(
//          e.msg.contains("Target definitions must have 0 parameter lists"),
//          e.pos.contains("def a() = ")
//        )
//      }
    }
    "badTmacro" - {
      // Make sure we can reference values from outside the Task {...} block as part
      // of our `Target#apply()` calls, but we cannot reference any values that
      // come from inside the Task {...} block
      "pos" - {
        val e = compileError("""
          val a = Task { 1 }
          val arr = Array(a)
          val b = {
            val c = 0
            Task {
              arr(c)()
            }
          }
        """)
        assert(e.msg.contains(
          "Modules, Targets and Commands can only be defined within a mill Module"
        ))
      }
      "neg" - {

        val expectedMsg =
          "Target#apply() call cannot use `value n` defined within the Task {...} block"
        val err = compileError("""new Module{
          def a = Task { 1 }
          val arr = Array(a)
          def b = {
            Task {
              val n = 0
              arr(n)()
            }
          }
        }""")
        assert(err.msg == expectedMsg)
      }
      "neg2" - {

        val expectedMsg =
          "Target#apply() call cannot use `value x` defined within the Task {...} block"
        val err = compileError("""new Module{
          def a = Task { 1 }
          val arr = Array(a)
          def b = {
            Task {
              arr.map{x => x()}
            }
          }
        }""")
        assert(err.msg == expectedMsg)
      }
      "neg3" - {
        val borkedCachedDiamond1 = utest.compileError("""
          object borkedCachedDiamond1 {
            def up = Task { TestUtil.test() }
            def left = Task { TestUtil.test(up) }
            def right = Task { TestUtil.test(up) }
            def down = Task { TestUtil.test(left, right) }
          }
        """)
        assert(borkedCachedDiamond1.msg.contains(
          "Modules, Targets and Commands can only be defined within a mill Module"
        ))
      }
    }

    "badCrossKeys" - {
      val error = utest.compileError(
        """
        object foo extends mill.util.TestUtil.BaseModule{
          object cross extends Cross[MyCrossModule](Seq(1, 2, 3))
          trait MyCrossModule extends Cross.Module[String]
        }
      """
      )
      assert(error.msg.contains("type mismatch;"))
      assert(error.msg.contains("found   : Int"))
      assert(error.msg.contains("required: String"))
    }

    "invalidCrossType" - {
      val error = utest.compileError(
        """
        object foo extends mill.util.TestUtil.BaseModule{
          object cross extends Cross[MyCrossModule](null.asInstanceOf[sun.misc.Unsafe])
          trait MyCrossModule extends Cross.Module[sun.misc.Unsafe]
        }
      """
      )
      assert(error.msg.contains(
        "could not find implicit value for evidence parameter of type mill.define.Cross.ToSegments[sun.misc.Unsafe]"
      ))
    }
  }
}
