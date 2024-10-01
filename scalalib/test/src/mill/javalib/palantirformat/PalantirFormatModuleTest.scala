package mill
package javalib.palantirformat

import mill.main.Tasks
import mill.scalalib.ScalaModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object PalantirFormatModuleTest extends TestSuite {

  def tests: Tests = Tests {

    val (before, after) = {
      val root = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "javalib" / "palantirformat"
      (root / "before", root / "after")
    }

    test("palantirformat") {

      assert(
        checkState(
          afterFormat(before / "google"),
          after / "google"
        ),
        checkState(
          afterFormat(before / "palantir"),
          after / "palantir"
        ),
        checkState(
          afterFormat(before / "palantir", sources = Seq("src/Main.java")),
          after / "palantir"
        )
      )

      intercept[RuntimeException](
        afterFormat(before / "palantir", check = true)
      )
    }

    test("formatAll") {

      assert(
        checkState(
          afterFormatAll(before / "google"),
          after / "google"
        ),
        checkState(
          afterFormatAll(before / "palantir"),
          after / "palantir"
        )
      )

      intercept[RuntimeException](
        afterFormatAll(before / "google", check = true)
      )
    }

    test("example") {

      intercept[RuntimeException](
        afterFormat(before / "example", check = true)
      )

      assert(
        checkState(
          afterFormat(before / "example"),
          after / "example"
        )
      )
    }
  }

  def checkState(actualFiles: Seq[os.Path], expectedRoot: os.Path): Boolean = {
    val expectedFiles = walkFiles(expectedRoot)
    actualFiles.length == expectedFiles.length &&
    actualFiles.iterator.zip(expectedFiles.iterator).forall {
      case (actualFile, expectedFile) =>
        val actual = os.read(actualFile)
        val expected = os.read(expectedFile)
        actual == expected
    }
  }

  def afterFormat(
      moduleRoot: os.Path,
      version: String = "2.50.0",
      check: Boolean = false,
      sources: Seq[String] = Seq.empty
  ): Seq[os.Path] = {

    object module extends TestBaseModule with ScalaModule with PalantirFormatModule {
      override def palantirformatVersion: T[String] = version
      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
    }

    val eval = UnitTester(module, moduleRoot)

    eval(module.palantirformat(mainargs.Flag(check), mainargs.Leftover(sources: _*))).fold(
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
    eval(PalantirFormatModule.formatAll(mainargs.Flag(check), Tasks(Seq(module.sources)))).fold(
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
