package mill.define

import mill.eval.Evaluator
import mill.util.{DummyLogger, TestUtil}
import mill.util.Strict.Agg
import mill.T
import mill.eval.Result.Success
import utest._
import utest.framework.TestPath

object CacherTests extends TestSuite {
  object Base extends Base
  trait Base extends TestUtil.BaseModule {
    def value = T { 1 }
    def result = T { Success(1) }
  }
  object Middle extends Middle
  trait Middle extends Base {
    def value = T { super.value() + 2 }
    def overriden = T { super.value() }
  }
  object Terminal extends Terminal
  trait Terminal extends Middle {
    override def value = T { super.value() + 4 }
  }

  val tests = Tests {

    def eval[V](mapping: mill.Module, v: Task[V])(implicit tp: TestPath) = {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / tp.value
      val evaluator =
        new Evaluator(workspace, ammonite.ops.pwd, mapping, DummyLogger)
      evaluator.evaluate(Agg(v)).values(0)
    }

    'simpleDefIsCached - assert(
      Base.value eq Base.value,
      eval(Base, Base.value) == 1
    )

    'resultDefIsCached - assert(
      Base.result eq Base.result,
      eval(Base, Base.result) == 1
    )

    'overridingDefIsAlsoCached - assert(
      eval(Middle, Middle.value) == 3,
      Middle.value eq Middle.value
    )

    'overridenDefRemainsAvailable - assert(eval(Middle, Middle.overriden) == 1)

    'multipleOverridesWork - assert(
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
