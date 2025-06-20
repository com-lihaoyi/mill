package mill.scalalib

import coursier.cache.FileCache
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.util.TokenReaders._

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestRootModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    sys.props("coursier.mirrors") = (resourcePath / "mirror.properties").toString
    test("readMirror") - UnitTester(CoursierTest, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(CoursierTest.core.compileClasspath): @unchecked
      val Right(cacheResult) = eval.apply(CoursierConfigModule.defaultCacheLocation): @unchecked
      val cacheRoot = os.Path(cacheResult.value)
      val cp = result.value
        .map(_.path)
        .filter(_.startsWith(cacheRoot))
        .map(_.relativeTo(cacheRoot).asSubPath)
      assert(cp.exists(f => f.last.startsWith("scala-library-") && f.last.endsWith(".jar")))
      val centralReplaced = cp.forall { f =>
        f.startsWith(os.sub / "https/repo.maven.apache.org/maven2")
      }
      assert(centralReplaced)
    }
  }
}
