package mill.contrib
package tut

import ammonite.ops._
import mill._
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath


object TutTests extends TestSuite {

  trait TutTestModule extends TestUtil.BaseModule with TutModule {
    def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    def scalaVersion = "2.12.4"
  }

  object TutTest extends TutTestModule

  object TutCustomTest extends TutTestModule {
    def tutTargetDirectory = millSourcePath
  }

  val resourcePath = pwd / 'contrib / 'tut / 'test / 'tut

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: Path = resourcePath)
                      (t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath)
    cp(resourcePath, m.millSourcePath / 'tut)
    t(eval)
  }

  def tests: Tests = Tests {
    'tut - {
      'createOutputFile - workspaceTest(TutTest) { eval =>
        val expectedPath =
          eval.outPath / 'tutTargetDirectory / 'dest / "TutExample.md"

        val expected =
          """
          |```scala
          |scala> 1 + 1
          |res0: Int = 2
          |```
          |
          """.trim.stripMargin

        val Right((result, evalCount)) = eval.apply(TutTest.tut)

        assert(
          exists(expectedPath) &&
            read! expectedPath == expected
        )
      }

      'supportCustomSettings - workspaceTest(TutCustomTest) { eval =>
        val defaultPath =
          eval.outPath / 'tutTargetDirectory / 'dest / "TutExample.md"
        val expectedPath =
          TutCustomTest.millSourcePath / "TutExample.md"

        val expected =
          """
          |```scala
          |scala> 1 + 1
          |res0: Int = 2
          |```
          |
          """.trim.stripMargin

        val Right((result, evalCount)) = eval.apply(TutCustomTest.tut)

        assert(
          !exists(defaultPath) &&
            exists(expectedPath) &&
            read! expectedPath == expected
        )
      }
    }
  }
}
