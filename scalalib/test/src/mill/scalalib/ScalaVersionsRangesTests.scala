package mill.scalalib

import mill._
import mill.testkit.TestEvaluator
import mill.testkit.MillTestKit

import utest._
import utest.framework.TestPath

object ScalaVersionsRangesTests extends TestSuite {
  object ScalaVersionsRanges extends mill.testkit.BaseModule {
    def millSourcePath = MillTestKit.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    object core extends Cross[CoreCrossModule]("2.12.13", "2.13.5", "3.3.3")
    trait CoreCrossModule extends CrossScalaModule
        with CrossScalaVersionRanges {
      object test extends ScalaTests with TestModule.Utest {
        def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
      }
    }
  }
  val resourcePath =
    os.pwd / "scalalib" / "test" / "resources" / "scala-versions-ranges"

  def workspaceTest[T](
      m: mill.testkit.BaseModule
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
