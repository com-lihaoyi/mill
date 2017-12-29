package mill.define

import utest._
import mill.{T, Module}
import mill.util.TestUtil
object MacroErrorTests extends TestSuite{

  val tests = Tests{

    'errors{
      val expectedMsg =
        "T{} members must be defs defined in a Cacher class/trait/object body"

      val err = compileError("object Foo extends Module{ val x = T{1} }")
      assert(err.msg == expectedMsg)
    }

    'badTmacro - {
      // Make sure we can reference values from outside the T{...} block as part
      // of our `Target#apply()` calls, but we cannot reference any values that
      // come from inside the T{...} block
      'pos - {
        val e = compileError("""
          val a = T{ 1 }
          val arr = Array(a)
          val b = {
            val c = 0
            T{
              arr(c)()
            }
          }
        """)
        assert(e.msg.contains("required: mill.define.Task.Module"))
      }
      'neg - {

        val expectedMsg =
          "Target#apply() call cannot use `value n` defined within the T{...} block"
        val err = compileError("""new Module{
          def a = T{ 1 }
          val arr = Array(a)
          def b = {
            T{
              val n = 0
              arr(n)()
            }
          }
        }""")
        assert(err.msg == expectedMsg)
      }
      'neg2 - {

        val expectedMsg =
          "Target#apply() call cannot use `value x` defined within the T{...} block"
        val err = compileError("""new Module{
          def a = T{ 1 }
          val arr = Array(a)
          def b = {
            T{
              arr.map{x => x()}
            }
          }
        }""")
        assert(err.msg == expectedMsg)
      }
      'neg3{
        val borkedCachedDiamond1 = utest.compileError("""
          object borkedCachedDiamond1 {
            def up = T{ TestUtil.test() }
            def left = T{ TestUtil.test(up) }
            def right = T{ TestUtil.test(up) }
            def down = T{ TestUtil.test(left, right) }
          }
        """)
        assert(borkedCachedDiamond1.msg.contains("required: mill.Module"))
      }
    }
  }
}
