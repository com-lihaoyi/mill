package mill
package contrib.palantirjavaformat

import mill.scalalib.ScalaModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object PalantirJavaFormatModuleTest extends TestSuite {

  def tests: Tests = Tests {

    // root folder for modules
    val modules: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))

    // root folder with directories containing state of sources after formatting for different test cases
    val expected: os.Path = modules / os.up / "expected-sources"

    test("javafmt") {
      assert(
        checkState(
          afterFormat(modules / "palantir"),
          expected / "palantir"
        ),
        checkState(
          afterFormat(modules / "palantir", sourceFilters = Seq("src/Main.java")),
          expected / "palantir"
        )
      )

      intercept[RuntimeException] {
        afterFormat(modules / "palantir", check = true)
      }
    }
  }

  def checkState(actualFiles: Seq[os.Path], expectedRoot: os.Path): Boolean = {
    val expectedFiles = walkFiles(expectedRoot)
    actualFiles.length == expectedFiles.length &&
    actualFiles.iterator.zip(expectedFiles.iterator).forall {
      case (actual, expected) =>
        val left = os.read(actual)
        val right = os.read(expected)
        left == right
    }
  }

  def afterFormat(
      moduleRoot: os.Path,
      version: String = "2.50.0",
      check: Boolean = false,
      sourceFilters: Seq[String] = Seq.empty
  ): Seq[os.Path] = {

    object mod extends TestBaseModule with ScalaModule with PalantirJavaFormatModule {
      override def palantirjavaformatVersion: T[String] = version
      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
    }

    val eval = UnitTester(mod, moduleRoot)

    eval(mod.javafmt(check, mainargs.Leftover(sourceFilters: _*))).fold(
      {
        case api.Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      { _ =>
        val Right(sources) = eval(mod.sources)

        sources.value.flatMap(ref => walkFiles(ref.path))
      }
    )
  }

  def walkFiles(root: os.Path): Seq[os.Path] =
    os.walk(root, includeTarget = true).filter(os.isFile)
}
