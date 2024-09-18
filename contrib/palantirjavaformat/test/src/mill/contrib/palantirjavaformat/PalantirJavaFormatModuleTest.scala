package mill
package contrib.palantirjavaformat

import mill.main.Tasks
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
          afterFormat(modules / "google"),
          expected / "google"
        ),
        checkState(
          afterFormat(modules / "palantir"),
          expected / "palantir"
        ),
        checkState(
          afterFormat(modules / "palantir", sources = Seq("src/Main.java")),
          expected / "palantir"
        )
      )

      intercept[RuntimeException] {
        afterFormat(modules / "palantir", check = true)
      }
    }

    test("formatAll") {
      assert(
        checkState(
          afterFormatAll(modules / "google"),
          expected / "google"
        ),
        checkState(
          afterFormatAll(modules / "palantir"),
          expected / "palantir"
        )
      )

      intercept[RuntimeException] {
        afterFormatAll(modules / "google", check = true)
      }
    }
  }

  def checkState(actualFiles: Seq[os.Path], expectedRoot: os.Path): Boolean = {
    val expectedFiles = walkFiles(expectedRoot)
    actualFiles.length == expectedFiles.length &&
    actualFiles.iterator.zip(expectedFiles.iterator).forall {
      case (actualFile, expectedFile) =>
        val actual = os.read(actualFile)
        val expected = os.read(expectedFile)
        if (actual != expected) println(actualFile)
        actual == expected
    }
  }

  def afterFormat(
      moduleRoot: os.Path,
      version: String = "2.50.0",
      check: Boolean = false,
      sources: Seq[String] = Seq.empty
  ): Seq[os.Path] = {

    object module extends TestBaseModule with ScalaModule with PalantirJavaFormatModule {
      override def palantirjavaformatVersion: T[String] = version
      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
    }

    val eval = UnitTester(module, moduleRoot)

    eval(module.javafmt(check, mainargs.Leftover(sources: _*))).fold(
      {
        case api.Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      { _ =>
        val Right(sources) = eval(module.sources)

        sources.value.flatMap(ref => walkFiles(ref.path))
      }
    )
  }

  def afterFormatAll(modulesRoot: os.Path, check: Boolean = false): Seq[os.Path] = {

    object module extends TestBaseModule with ScalaModule {
      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
    }

    val eval = UnitTester(module, modulesRoot)
    eval(PalantirJavaFormatModule.formatAll(check, Tasks(Seq(module.sources)))).fold(
      {
        case api.Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      { _ =>
        val Right(sources) = eval(module.sources)
        sources.value.flatMap(ref => walkFiles(ref.path))
      }
    )
  }

  def walkFiles(root: os.Path): Seq[os.Path] =
    os.walk(root).filter(os.isFile)
}
