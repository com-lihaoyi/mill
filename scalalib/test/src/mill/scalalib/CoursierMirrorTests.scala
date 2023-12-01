package mill.scalalib

import mill.util.{TestEvaluator, TestUtil}
import mill.eval.{Evaluator}
import utest._
import utest.framework.TestPath

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "coursier"

  object CoursierTest extends TestUtil.BaseModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
    }
  }

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath,
      env: Map[String, String] = Evaluator.defaultEnv,
      debug: Boolean = false
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, env = env, debugEnabled = debug)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    sys.props("coursier.mirrors") = (resourcePath / "mirror.properties").toString
    "readMirror" - workspaceTest(CoursierTest) { eval =>
      val Right((result, evalCount)) = eval.apply(CoursierTest.core.repositoriesTask)
      val centralReplaced = result.exists { repo =>
        repo.repr.contains("https://repo.maven.apache.org/maven2")
      }
      assert(centralReplaced)
    }
  }
}
