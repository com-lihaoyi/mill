package forge

import utest._

object MacroErrorTests extends TestSuite{

  val tests = Tests{

    'errors{
      val expectedMsg =
        "T{} members defined in a Cacher class/trait/object body must be defs"

      val err = compileError("object Foo extends Target.Cacher{ val x = T{1} }")
      assert(err.msg == expectedMsg)
    }
    'badTmacro - {
      // Make sure we can reference values from outside the T{...} block as part
      // of our `Target#apply()` calls, but we cannot reference any values that
      // come from inside the T{...} block
      'pos - {
        val a = T{ 1 }
        val arr = Array(a)
        val b = {
          val c = 0
          T{
            arr(c)()
          }
        }
      }
      'neg - {

        val expectedMsg =
          "Target#apply() call cannot use `value n` defined within the T{...} block"
        val err = compileError("""{
          val a = T{ 1 }
          val arr = Array(a)
          val b = {
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
        val err = compileError("""{
          val a = T{ 1 }
          val arr = Array(a)
          val b = {
            T{
              arr.map{x => x()}
            }
          }
        }""")
        assert(err.msg == expectedMsg)
      }
    }
  }
}
