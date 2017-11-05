package forge

import forge.util.OSet
import utest._
import utest.framework.TestPath

object CacherTests extends TestSuite{
  object Base extends Base
  trait Base extends Target.Cacher{
    def value = T{ 1 }
  }
  object Middle extends Middle
  trait Middle extends Base{
    override def value = T{ super.value() + 2}
    def overriden = T{ super.value()}
  }
  object Terminal extends Terminal
  trait Terminal extends Middle{
    override def value = T{ super.value() + 4}
  }

  val tests = Tests{


    def eval[T: Discovered, V](base: T, v: Target[V])(implicit tp: TestPath) = {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / tp.value
      val evaluator = new Evaluator(workspace, Discovered.mapping(base))
      evaluator.evaluate(OSet(v)).values(0)
    }

    'simpleDefIsCached - assert(
      Base.value eq Base.value,
      eval(Base, Base.value) == 1
    )

    'overridingDefIsAlsoCached - assert(
      eval(Middle, Middle.value) == 3,
      Middle.value eq Middle.value
    )

    'overridenDefRemainsAvailable - assert(
      eval(Middle, Middle.overriden) == 1
    )

    'multipleOverridesWork- assert(
      eval(Terminal, Terminal.value) == 7,
      eval(Terminal, Terminal.overriden) == 1
    )
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
    }
  }
}
