package mill.define

import mill.testkit.TestEvaluator
import mill.testkit.MillTestKit
import mill.T
import mill.api.Result.Success
import utest._
import utest.framework.TestPath

object CacherTests extends TestSuite {
  object Base extends Base
  trait Base extends mill.testkit.BaseModule {
    def value = T { 1 }
    def result = T { Success(1) }
  }
  object Middle extends Middle
  trait Middle extends Base {
    override def value = T { super.value() + 2 }
    def overridden = T { super.value() }
  }
  object Terminal extends Terminal
  trait Terminal extends Middle {
    override def value = T { super.value() + 4 }
  }

  val tests = Tests {
    def eval[T <: mill.testkit.BaseModule, V](mapping: T, v: Task[V])(implicit tp: TestPath) = {
      val evaluator = new TestEvaluator(mapping)
      evaluator(v).toOption.get._1
    }

    test("simpleDefIsCached") {
      Predef.assert(Base.value eq Base.value)
      Predef.assert(eval(Base, Base.value) == 1)
    }

    test("resultDefIsCached") {
      Predef.assert(Base.result eq Base.result)
      Predef.assert(eval(Base, Base.result) == 1)
    }

    test("overridingDefIsAlsoCached") {
      Predef.assert(eval(Middle, Middle.value) == 3)
      Predef.assert(Middle.value eq Middle.value)
    }

    test("overriddenDefRemainsAvailable") {
      Predef.assert(eval(Middle, Middle.overridden) == 1)
    }

    test("multipleOverridesWork") {
      Predef.assert(eval(Terminal, Terminal.value) == 7)
      Predef.assert(eval(Terminal, Terminal.overridden) == 1)
    }
    //    Doesn't fail, presumably compileError doesn't go far enough in the
    //    compilation pipeline to hit the override checks
    //
    //    test("overrideOutsideModuleFails") {
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
