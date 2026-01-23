package mill.javalib

import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "coursier"

  object CoursierTest extends TestRootModule {
    object core extends JavaModule {
      def mvnDeps = Seq(mvn"com.google.guava:guava:33.0.0-jre")
    }

    lazy val millDiscover = Discover[this.type]
  }

  def withJavaProp[T](name: String, value: String)(thunk: => T): T = {
    // might not be fine with concurrent uses / changes of the same property,
    // but doing that is better than setting the value and forgetting about it
    val formerValueOpt = sys.props.get(name)
    try {
      sys.props(name) = value
      thunk
    } finally {
      formerValueOpt match {
        case None =>
          sys.props.remove(name)
        case Some(formerValue) =>
          sys.props(name) = formerValue
      }
    }
  }

  def tests: Tests = Tests {
    test("readMirror") {
      withJavaProp("coursier.mirrors", (resourcePath / "mirror.properties").toString) {
        UnitTester(CoursierTest, resourcePath).scoped { eval =>
          val Right(result) = eval.apply(CoursierTest.core.compileClasspath).runtimeChecked
          val Right(cacheResult) = eval.apply(CoursierConfigModule.defaultCacheLocation).runtimeChecked
          val cacheRoot = os.Path(cacheResult.value)
          val cp = result.value
            .map(_.path)
            .filter(_.startsWith(cacheRoot))
            .map(_.relativeTo(cacheRoot).asSubPath)
          assert(cp.exists(f => f.last.startsWith("guava-") && f.last.endsWith(".jar")))
          val centralReplaced = cp.forall { f =>
            f.startsWith(os.sub / "https/repo.maven.apache.org/maven2")
          }
          assert(centralReplaced)
        }
      }
    }
  }
}
