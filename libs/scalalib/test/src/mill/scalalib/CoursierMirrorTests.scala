package mill.scalalib

// import coursier.cache.FileCache
import mill.api.Discover
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
      // Doesn't pass when other tests are running before/after in the same JVM
      //      val Right(result) = eval.apply(CoursierTest.core.compileClasspath): @unchecked
      //      val cacheRoot = os.Path(FileCache().location)
      //      val cp = result.value
      //        .map(_.path)
      //        .filter(_.startsWith(cacheRoot))
      //        .map(_.relativeTo(cacheRoot).asSubPath)
      //      assert(cp.exists(f => f.last.startsWith("scala-library-") && f.last.endsWith(".jar")))
      //      pprint.log(cp)
      //      val centralReplaced = cp.forall { f =>
      //        f.startsWith(os.sub / "https/repo.maven.apache.org/maven2")
      //      }
      //      assert(centralReplaced)
    }
  }
}
