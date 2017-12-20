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


    def eval[V](mapping: Discovered.Mapping[_], v: Task[V])(implicit tp: TestPath) = {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / tp.value
      val evaluator = new Evaluator(workspace, mapping.value, DummyLogger)
      evaluator.evaluate(OSet(v)).values(0)
    }

    'simpleDefIsCached - assert(
      Base.value eq Base.value,
      eval(Discovered.mapping(Base), Base.value) == 1
    )

    val middleMapping = Discovered.mapping(Middle)

    'overridingDefIsAlsoCached - assert(
      eval(middleMapping, Middle.value) == 3,
      Middle.value eq Middle.value
    )

    'overridenDefRemainsAvailable - assert(
      eval(middleMapping, Middle.overriden) == 1
    )

    val terminalMapping = Discovered.mapping(Terminal)

    'multipleOverridesWork- assert(
      eval(terminalMapping, Terminal.value) == 7,
      eval(terminalMapping, Terminal.overriden) == 1
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

