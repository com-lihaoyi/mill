package mill.scalalib

import mill.define.{Discover, Task}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.util.TokenReaders._
import utest.*

object CoursierParametersTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
      def ivyDeps = Task {
        Seq(ivy"com.lihaoyi::pprint:0.9.0")
      }
      def resolutionParams = Task.Anon {
        super.resolutionParams()
          .addForceVersion((
            coursier.Module(
              coursier.Organization("com.lihaoyi"),
              coursier.ModuleName("pprint_2.13")
            ),
            "0.8.1"
          ))
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("coursierParams") - UnitTester(CoursierTest, null).scoped { eval =>
      val Right(result) = eval.apply(CoursierTest.core.compileClasspath): @unchecked
      val classPath = result.value.toSeq.map(_.path)
      val pprintVersion = classPath
        .map(_.last)
        .filter(_.endsWith(".jar"))
        .filter(_.startsWith("pprint_2.13-"))
        .map(_.stripPrefix("pprint_2.13-").stripSuffix(".jar"))
        .headOption
        .getOrElse {
          sys.error(s"pprint not found in class path $classPath")
        }
      val expectedPprintVersion = "0.8.1"
      assert(pprintVersion == expectedPprintVersion)
    }
  }
}
