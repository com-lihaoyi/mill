package mill.define

import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.{DummyLogger, OSet}
import mill.T
import utest._
import utest.framework.TestPath

object CacherTests extends TestSuite{
  object Base extends Base
  trait Base extends Task.Module{
    def value = T{ 1 }
  }
  object Middle extends Middle
  trait Middle extends Base{
    def value = T{ super.value() + 2}
    def overriden = T{ super.value()}
  }
  object Terminal extends Terminal
  trait Terminal extends Middle{
    override def value = T{ super.value() + 4}
  }

  val tests = Tests{


    def eval[T: Discovered, V](base: T, v: Task[V])(implicit tp: TestPath) = {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / tp.value
      val evaluator = new Evaluator(workspace, Discovered.mapping(base), DummyLogger)
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
    //    Doesn't fail, presumably compileError doesn't go far enough in the
    //    compilation pipeline to hit the override checks
    //
    //    'overrideOutsideModuleFails - {
    //      compileError("""
    //        trait Foo{
    //          def x = 1
    //        }
    //        object Bar extends Foo{
    //          def x = 2
    //        }
    //      """)
    //    }
  }
}

