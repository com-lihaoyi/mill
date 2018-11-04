package mill.define

import mill.util.{DummyLogger, TestEvaluator, TestUtil}
import mill.util.Strict.Agg
import mill.T
import mill.eval.Result.Success
import utest._
import utest.framework.TestPath


object CacherTests extends TestSuite{
  object Base extends Base
  trait Base extends TestUtil.BaseModule{
    def value = T{ 1 }
    def result = T{ Success(1) }
  }
  object Middle extends Middle
  trait Middle extends Base{
    override def value = T{ super.value() + 2}
    def overriden = T{ super.value()}
  }
  object Terminal extends  Terminal
  trait Terminal extends Middle{
    override def value = T{ super.value() + 4}
  }

  val tests = Tests{
    def eval[T <: TestUtil.BaseModule, V](mapping: T, v: Task[V])
                                         (implicit tp: TestPath) = {
      val evaluator = new TestEvaluator(mapping)
      evaluator(v).right.get._1
    }
    def check(x: Any, y: Any) = assert(x == y)

    'simpleDefIsCached - {
      Predef.assert(Base.value eq Base.value)
      Predef.assert(eval(Base, Base.value) == 1)
    }

    'resultDefIsCached - {
      Predef.assert(Base.result eq Base.result)
      Predef.assert(eval(Base, Base.result) == 1)
    }


    'overridingDefIsAlsoCached - {
      Predef.assert(eval(Middle, Middle.value) == 3)
      Predef.assert(Middle.value eq Middle.value)
    }

    'overridenDefRemainsAvailable - {
      Predef.assert(eval(Middle, Middle.overriden) == 1)
    }


    'multipleOverridesWork- {
      Predef.assert(eval(Terminal, Terminal.value) == 7)
      Predef.assert(eval(Terminal, Terminal.overriden) == 1)
    }
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

