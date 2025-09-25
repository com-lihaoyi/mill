package mill.define

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.{Task}
import mill.api.Result.Success
import utest._
import utest.framework.TestPath

object CacherTests extends TestSuite {
  object Base extends Base
  trait Base extends TestBaseModule {
    def value = Task { 1 }
    def result = Task { Success(1) }
  }
  object Middle extends Middle
  trait Middle extends Base {
    override def value = Task { super.value() + 2 }
    def overridden = Task { super.value() }
  }
  object Terminal extends Terminal
  trait Terminal extends Middle {
    override def value = Task { super.value() + 4 }
  }

  val tests = Tests {
    def eval[T <: mill.testkit.TestBaseModule, V](mapping: T, v: Task[V])(implicit tp: TestPath) = {
      val evaluator = UnitTester(mapping, null)
      evaluator(v).toOption.get.value
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
