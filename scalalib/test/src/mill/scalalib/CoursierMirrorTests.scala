package mill.scalalib

import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*
import mill.util.TokenReaders._

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    sys.props("coursier.mirrors") = (resourcePath / "mirror.properties").toString
    test("readMirror") - UnitTester(CoursierTest, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(CoursierTest.core.repositoriesTask): @unchecked
      val centralReplaced = result.value.exists { repo =>
        repo.repr.contains("https://repo.maven.apache.org/maven2")
      }
      assert(centralReplaced)
    }
  }
}
