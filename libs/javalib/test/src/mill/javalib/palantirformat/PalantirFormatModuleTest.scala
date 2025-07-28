package mill
package javalib.palantirformat

import mill.api.Discover
import mill.util.Tasks
import mill.javalib.JavaModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object PalantirFormatModuleTest extends TestSuite {

  def tests: Tests = Tests {

    val (before, after) = {
      val root = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "javalib/palantirformat"
      (root / "before", root / "after")
    }

    test("palantirformat") {

      assertAll(
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
        ),
        checkState(
          afterFormat(before / "empty"),
          after / "empty"
        )
      )

      assertThrows[RuntimeException](
        afterFormat(before / "palantir", check = true)
      )
    }

    test("formatAll") {

      assertAll(
        checkState(
          afterFormatAll(before / "google"),
          after / "google"
        ),
        checkState(
          afterFormatAll(before / "palantir"),
          after / "palantir"
        ),
        checkState(
          afterFormatAll(before / "empty"),
          after / "empty"
        )
      )

      assertThrows[RuntimeException](
        afterFormatAll(before / "google", check = true)
      )
    }

    test("example") {

      assertThrows[RuntimeException](
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

    object module extends TestRootModule with JavaModule with PalantirFormatModule {
      override def palantirformatVersion: T[String] = version
      lazy val millDiscover = Discover[this.type]
    }

    UnitTester(module, moduleRoot).scoped { eval =>
      eval(module.palantirformat(mainargs.Flag(check), mainargs.Leftover(sources*))).fold(
        _.throwException,
        { _ =>
          val Right(sources) = eval(module.sources): @unchecked

          sources.value.flatMap(ref => walkFiles(ref.path))
        }
      )
    }
  }

  def afterFormatAll(modulesRoot: os.Path, check: Boolean = false): Seq[os.Path] = {

    object module extends TestRootModule with JavaModule {

      lazy val millDiscover = Discover[this.type]
    }

    UnitTester(module, modulesRoot).scoped { eval =>
      eval(PalantirFormatModule.formatAll(mainargs.Flag(check), Tasks(Seq(module.sources)))).fold(
        _.throwException,
        { _ =>
          val Right(sources) = eval(module.sources): @unchecked
          sources.value.map(_.path).flatMap(walkFiles(_))
        }
      )
    }
  }

  def walkFiles(root: os.Path): Seq[os.Path] = {
    if (os.exists(root)) os.walk(root).filter(p => os.isFile(p) && p.last != ".keep")
    else Nil
  }
}
