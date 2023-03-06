package mill.scalalib

import mill.scalalib.HelloWorldTests.HelloWorldScalaOverride
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, compileError, intercept, test}

object CycleTests extends TestSuite {

  object CycleBase extends TestUtil.BaseModule {
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
  }

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    t(eval)
  }

  override def tests: Tests = Tests {
    test("moduleDeps") {
      test("self-reference") - workspaceTest(CycleBase) { eval =>
        val ex = intercept[IllegalStateException] {
          eval.apply(CycleBase.a.compile)
        }
        assert(ex.getMessage == "moduleDeps: cycle detected: a -> a")
      }
      test("cycle-in-deps") - workspaceTest(CycleBase) { eval =>
        val ex = intercept[IllegalStateException] {
          eval.apply(CycleBase.e.compile)
        }
        assert(ex.getMessage == "moduleDeps: cycle detected: b -> b.c -> b.d -> b")
      }
    }
    test("compileModuleDeps") {
      test("self-reference") - workspaceTest(CycleBase) { eval =>
        val ex = intercept[IllegalStateException] {
          eval.apply(CycleBase.f.compile)
        }
        assert(ex.getMessage == "compileModuleDeps: cycle detected: f -> f")
      }
    }
  }
}
