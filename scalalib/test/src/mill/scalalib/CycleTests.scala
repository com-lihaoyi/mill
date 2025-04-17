package mill.scalalib

import mill.api.BuildScriptException
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.{TestSuite, Tests, assert, intercept, test}
import mill.util.TokenReaders._

object CycleTests extends TestSuite {

  object CycleBase extends TestBaseModule {
    // See issue: https://github.com/com-lihaoyi/mill/issues/2341
    object a extends ScalaModule {
      override def moduleDeps = Seq(a)
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_VERSION", ???)
    }
    object b extends JavaModule {
      override def moduleDeps = Seq(c)
      object c extends JavaModule {
        override def moduleDeps = Seq(d)
      }
      object d extends JavaModule {
        override def moduleDeps = Seq(b)
      }
    }
    object e extends JavaModule {
      override def moduleDeps = Seq(b)
    }
    object f extends JavaModule {
      override def compileModuleDeps = Seq(f)
    }

    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {
    test("moduleDeps") {
      test("self-reference") - UnitTester(CycleBase, null).scoped { eval =>
        val ex = intercept[BuildScriptException] {
          eval.apply(CycleBase.a.compile)
        }
        assert(ex.getMessage.contains("a.moduleDeps: cycle detected: a -> a"))
      }
      test("cycle-in-deps") - UnitTester(CycleBase, null).scoped { eval =>
        val ex = intercept[BuildScriptException] {
          eval.apply(CycleBase.e.compile)
        }
        assert(ex.getMessage.contains("e.moduleDeps: cycle detected: b -> b.c -> b.d -> b"))
      }
    }
    test("compileModuleDeps") {
      test("self-reference") - UnitTester(CycleBase, null).scoped { eval =>
        val ex = intercept[BuildScriptException] {
          eval.apply(CycleBase.f.compile)
        }
        assert(ex.getMessage.contains("f.compileModuleDeps: cycle detected: f -> f"))
      }
    }
  }
}
