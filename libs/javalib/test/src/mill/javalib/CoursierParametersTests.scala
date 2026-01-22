package mill.javalib

import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object CoursierParametersTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestRootModule {
    object core extends JavaModule {
      def mvnDeps = Task {
        Seq(mvn"com.google.guava:guava:33.0.0-jre")
      }
      def resolutionParams = Task.Anon {
        super.resolutionParams()
          .addForceVersion((
            coursier.Module(
              coursier.Organization("com.google.guava"),
              coursier.ModuleName("guava")
            ),
            "32.1.3-jre"
          ))
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("coursierParams") - UnitTester(CoursierTest, null).scoped { eval =>
      val Right(result) = eval.apply(CoursierTest.core.compileClasspath): @unchecked
      val classPath = result.value.toSeq.map(_.path)
      val guavaVersion = classPath
        .map(_.last)
        .filter(_.endsWith(".jar"))
        .filter(_.startsWith("guava-"))
        .map(_.stripPrefix("guava-").stripSuffix(".jar").stripSuffix("-jre"))
        .headOption
        .getOrElse {
          sys.error(s"guava not found in class path $classPath")
        }
      val expectedGuavaVersion = "32.1.3"
      assert(guavaVersion == expectedGuavaVersion)
    }
  }
}
