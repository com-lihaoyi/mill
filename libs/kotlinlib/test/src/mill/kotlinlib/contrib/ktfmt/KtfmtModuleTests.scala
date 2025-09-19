package mill.kotlinlib.ktfmt

import mill.api.Discover
import mill.{PathRef, T, api}
import mill.kotlinlib.KotlinModule
import mill.util.Tasks
import mill.testkit.{TestRootModule, UnitTester}
import utest.{TestSuite, Tests, assert, test}
import mill.util.TokenReaders._
object KtfmtModuleTests extends TestSuite {

  val kotlinVersion = "1.9.24"

  object module extends TestRootModule with KotlinModule with KtfmtModule {
    override def kotlinVersion: T[String] = KtfmtModuleTests.kotlinVersion

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    val (before, after) = {
      val root = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "contrib/ktfmt"
      (root / "before", root / "after")
    }

    test("ktfmt - kotlin style") {
      if (!scala.util.Properties.isJavaAtLeast(17)) "Ktfmt requires Java 17"
      else
        assert(
          checkState(
            afterFormat(before, style = "kotlin"),
            after / "style/kotlin"
          )
        )
    }

    test("ktfmt - google style") {
      if (!scala.util.Properties.isJavaAtLeast(17)) "Ktfmt requires Java 17"
      else
        assert(
          checkState(
            afterFormat(before, style = "google"),
            after / "style/google"
          )
        )
    }

    test("ktfmt - meta style") {
      if (!scala.util.Properties.isJavaAtLeast(17)) "Ktfmt requires Java 17"
      else
        assert(
          checkState(
            afterFormat(before, style = "meta"),
            after / "style/meta"
          )
        )
    }

    test("ktfmt - dry-run") {
      if (!scala.util.Properties.isJavaAtLeast(17)) "Ktfmt requires Java 17"
      else
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
        afterFormat(before, sources = Seq(module.sources)),
        after / "style/kotlin"
      )
    }

    test("formatAll") {
      if (!scala.util.Properties.isJavaAtLeast(17)) "Ktfmt requires Java 17"
      else
        assert(
          checkState(
            afterFormatAll(before),
            after / "style/kotlin"
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
      sources: Seq[mill.api.Task.Named[Seq[PathRef]]] = Seq.empty
  ): Seq[os.Path] = {

    UnitTester(module, moduleRoot).scoped { eval =>

      eval(module.ktfmt(
        KtfmtArgs(
          style = style,
          format = format,
          removeUnusedImports = removeUnusedImports
        ),
        sources = Tasks(sources)
      )).get

      val Right(sources2) = eval(module.sources): @unchecked

      sources2.value.flatMap(ref => walkFiles(ref.path))
    }
  }

  def afterFormatAll(modulesRoot: os.Path, format: Boolean = true): Seq[os.Path] = {

    object module extends TestRootModule with KotlinModule {
      override def kotlinVersion: T[String] = KtfmtModuleTests.kotlinVersion

      lazy val millDiscover = Discover[this.type]
    }

    UnitTester(module, modulesRoot).scoped { eval =>
      eval(KtfmtModule.formatAll(
        KtfmtArgs(
          style = "kotlin",
          format = format,
          removeUnusedImports = true
        ),
        sources = Tasks(Seq(module.sources))
      )).fold(_.get, _.value)
      val Right(sources) = eval(module.sources): @unchecked
      sources.value.flatMap(ref => walkFiles(ref.path))
    }
  }
  def walkFiles(root: os.Path): Seq[os.Path] =
    os.walk(root).filter(os.isFile)
}
