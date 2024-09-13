package mill
package contrib.palantirjavaformat

import mill.scalalib.ScalaModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object PalantirJavaFormatModuleTest extends TestSuite {

  def tests: Tests = Tests {

    // root folder for module
    val module: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))

    // root folder with directories containing state of sources after formatting for different test cases
    val expected: os.Path = module / os.up / "expected"

    test("javafmt") {
      assert(
        checkState(
          afterFormat(module),
          walkFiles(expected / "palantir")
        ),
        checkState(
          afterFormat(module, sources = Seq("src/Main.java")),
          walkFiles(expected / "palantir")
        )
      )

      intercept[RuntimeException] {
        afterFormat(module, check = true)
      }
    }
  }

  def checkState(actualFiles: Seq[os.Path], expectedFiles: Seq[os.Path]): Boolean = {
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
      styleOverride: Option[String] = Some("palantir"),
      skipSortImports: Boolean = false,
      skipUnusedImports: Boolean = false,
      skipReflowingLongStrings: Boolean = false,
      check: Boolean = false,
      sources: Seq[String] = Seq.empty
  ): Seq[os.Path] = {

    object mod extends TestBaseModule with ScalaModule with PalantirJavaFormatModule {
      override def palantirOptions: T[PalantirJavaFormatOptions] =
        PalantirJavaFormatOptions(styleOverride, skipSortImports, skipUnusedImports, skipReflowingLongStrings)

      override def palantirVersion: T[String] = version

      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
    }

    val eval = UnitTester(mod, moduleRoot)

    eval(mod.javafmt(check, mainargs.Leftover(sources: _*))).fold(
      {
        case api.Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      { _ =>
        val Right(moduleSources) = eval(mod.sources)

        moduleSources.value.flatMap(ref => walkFiles(ref.path))
      }
    )
  }

  def walkFiles(root: os.Path): Seq[os.Path] =
    os.walk(root, includeTarget = true).filter(os.isFile)
}
