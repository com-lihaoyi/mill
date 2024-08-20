package mill.scalalib

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.eval.{Evaluator}
import utest._
import utest.framework.TestPath

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "coursier"

  object CoursierTest extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
    }
  }

  def workspaceTest[T](
                        m: mill.testkit.TestBaseModule,
                        resourcePath: os.Path = resourcePath,
                        env: Map[String, String] = Evaluator.defaultEnv,
                        debug: Boolean = false
  )(t: UnitTester => T)(implicit tp: TestPath): T = {
    val eval = new UnitTester(m, env = env, debugEnabled = debug)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    sys.props("coursier.mirrors") = (resourcePath / "mirror.properties").toString
    test("readMirror") - workspaceTest(CoursierTest) { eval =>
      val Right(result) = eval.apply(CoursierTest.core.repositoriesTask)
      val centralReplaced = result.value.exists { repo =>
        repo.repr.contains("https://repo.maven.apache.org/maven2")
      }
      assert(centralReplaced)
    }
  }
}
