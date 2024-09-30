package mill.kotlinlib.contrib.ktfmt

import mainargs.Leftover
import mill.{T, api}
import mill.kotlinlib.KotlinModule
import mill.kotlinlib.ktfmt.{KtfmtArgs, KtfmtModule}
import mill.main.Tasks
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, test}

object KtfmtModuleTests extends TestSuite {
  def tests: Tests = Tests {

    val (before, after) = {
      val root = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "contrib" / "ktfmt"
      (root / "before", root / "after")
    }

    test("ktfmt - kotlin style") {
      assert(
        checkState(
          afterFormat(before, style = "kotlin"),
          after / "style" / "kotlin"
        )
      )
    }

    test("ktfmt - google style") {
      assert(
        checkState(
          afterFormat(before, style = "google"),
          after / "style" / "google"
        )
      )
    }

    test("ktfmt - meta style") {
      assert(
        checkState(
          afterFormat(before, style = "meta"),
          after / "style" / "meta"
        )
      )
    }

    test("ktfmt - dry-run") {
      checkState(
        afterFormat(before, format = true),
        before
      )
    }

    test("ktfmt - don't remove unused imports") {
      checkState(
        afterFormat(before, removeUnusedImports = false),
        after / "imports"
      )
    }

    test("ktfmt - explicit files") {
      checkState(
        afterFormat(before, sources = Seq("src/Example.kt")),
        after / "style" / "kotlin"
      )
    }

    test("formatAll") {

      assert(
        checkState(
          afterFormatAll(before),
          after / "style" / "kotlin"
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
      style: String = "kotlin",
      format: Boolean = true,
      removeUnusedImports: Boolean = true,
      sources: Seq[String] = Seq.empty
  ): Seq[os.Path] = {

    object module extends TestBaseModule with KotlinModule with KtfmtModule {
      override def kotlinVersion: T[String] = "1.9.24"
    }

    val eval = UnitTester(module, moduleRoot)

    eval(module.ktfmt(
      KtfmtArgs(
        style = style,
        format = format,
        removeUnusedImports = removeUnusedImports
      ),
      sources = Leftover(sources: _*)
    )).fold(
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

  def afterFormatAll(modulesRoot: os.Path, format: Boolean = true): Seq[os.Path] = {

    object module extends TestBaseModule with KotlinModule {
      override def kotlinVersion: T[String] = "1.9.24"
    }

    val eval = UnitTester(module, modulesRoot)
    eval(KtfmtModule.formatAll(
      KtfmtArgs(
        style = "kotlin",
        format = format,
        removeUnusedImports = true
      ),
      sources = Tasks(Seq(module.sources))
    )).fold(
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
