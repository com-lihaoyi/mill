package mill.scalalib

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.eval.{Evaluator}
import utest._
import utest.framework.TestPath

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
    }
  }

  def tests: Tests = Tests {
    sys.props("coursier.mirrors") = (resourcePath / "mirror.properties").toString
    test("readMirror") - UnitTester(CoursierTest, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(CoursierTest.core.repositoriesTask)
      val centralReplaced = result.value.exists { repo =>
        repo.repr.contains("https://repo.maven.apache.org/maven2")
      }
      assert(centralReplaced)
    }
  }
}
