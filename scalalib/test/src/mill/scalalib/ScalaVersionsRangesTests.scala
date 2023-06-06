package mill.scalalib

import mill._
import mill.scalalib.api.ZincWorkerUtil
import mill.util.{TestEvaluator, TestUtil}

import utest._
import utest.framework.TestPath

object ScalaVersionsRangesTests extends TestSuite {
  object ScalaVersionsRanges extends TestUtil.BaseModule {
    def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    object core extends Cross[CoreCrossModule]("2.11.12", "2.12.13", "2.13.5", "3.0.0-RC2")
    trait CoreCrossModule extends CrossScalaModule
        with CrossScalaVersionRanges {
      object test extends ScalaModuleTests with TestModule.Utest {
        def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.8")
      }
    }
  }
  val resourcePath =
    os.pwd / "scalalib" / "test" / "resources" / "scala-versions-ranges"

  def workspaceTest[T](
      m: TestUtil.BaseModule
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, ScalaVersionsRanges.millSourcePath)
    t(eval)
  }

  val tests = Tests {
    test("main with Scala 2.12- and 2.13+ specific code") {
      workspaceTest(ScalaVersionsRanges) { eval =>
        ScalaVersionsRanges.core.crossModules.map { c =>
          val Right(_) = eval(c.run())
        }
      }
    }
    test("test with Scala 2.12- and 2.13+ specific code") {
      workspaceTest(ScalaVersionsRanges) { eval =>
        ScalaVersionsRanges.core.crossModules.map { c =>
          val Right(_) = eval(c.test.test())
        }
      }
    }
  }
}
