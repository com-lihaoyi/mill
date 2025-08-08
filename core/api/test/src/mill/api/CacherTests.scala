package mill.api

import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import mill.Task
import mill.api.Result.Success
import utest._

object CacherTests extends TestSuite {
  object Base extends Base {
    lazy val millDiscover = Discover[this.type]
  }
  trait Base extends TestRootModule {
    def value = Task { 1 }
    def result = Task { Success(1) }
  }
  object Middle extends Middle {
    lazy val millDiscover = Discover[this.type]
  }
  trait Middle extends Base {
    override def value = Task { super.value() + 2 }
    def overridden = Task { super.value() }
  }
  object Terminal extends Terminal {
    lazy val millDiscover = Discover[this.type]
  }
  trait Terminal extends Middle {
    override def value = Task { super.value() + 4 }
  }

  val tests = Tests {
    def eval[T <: mill.testkit.TestRootModule, V](mapping: T, v: Task[V]) = {
      UnitTester(mapping, null).scoped { evaluator =>
        evaluator(v).toOption.get.value
      }
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
    //      assertCompileError("""
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
