package mill.contrib
package tut

import mill._
import mill.eval.Result._
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object TutTests extends TestSuite {

  trait TutTestModule extends TestUtil.BaseModule with TutModule {
    def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    def scalaVersion = "2.12.4"
    def tutVersion = "0.6.7"
  }

  object TutTest extends TutTestModule

  object TutCustomTest extends TutTestModule {
    def tutTargetDirectory = millSourcePath
  }

  object TutLibrariesTest extends TutTestModule {
    def ivyDeps = Agg(ivy"org.typelevel::cats-core:1.4.0")
    def tutSourceDirectory = T.sources { resourcePathWithLibraries }
    def scalacPluginIvyDeps = Agg(ivy"org.spire-math::kind-projector:0.9.8")
  }

  val resourcePath = os.pwd / 'contrib / 'tut / 'test / 'tut
  val resourcePathWithLibraries = os.pwd / 'contrib / 'tut / 'test / "tut-with-libraries"

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: os.Path = resourcePath)
                      (t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath)
    os.copy(resourcePath, m.millSourcePath / 'tut)
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
          os.exists(expectedPath) &&
          os.read(expectedPath) == expected
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
          !os.exists(defaultPath) &&
            os.exists(expectedPath) &&
            os.read(expectedPath) == expected
        )
      }

      'supportUsingLibraries - workspaceTest(TutLibrariesTest, resourcePath = resourcePathWithLibraries) { eval =>
        val expectedPath =
          eval.outPath / 'tutTargetDirectory / 'dest / "TutWithLibraries.md"

        val expected =
          """
            |```scala
            |import cats._
            |import cats.arrow.FunctionK
            |import cats.implicits._
            |```
            |
            |```scala
            |scala> List(1, 2, 3).combineAll
            |res0: Int = 6
            |
            |scala> λ[FunctionK[List, Option]](_.headOption)(List(1, 2 ,3))
            |res1: Option[Int] = Some(1)
            |```
            |
          """.trim.stripMargin

        val Right(_) = eval.apply(TutLibrariesTest.tut)

        assert(
          os.exists(expectedPath) &&
          os.read(expectedPath) == expected
        )
      }
    }
  }
}
