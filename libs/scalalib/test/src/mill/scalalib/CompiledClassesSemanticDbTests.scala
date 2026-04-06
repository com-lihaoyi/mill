package mill.scalalib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

// Tests that compiledClassesAndSemanticDbFiles includes files from module dependencies
object CompiledClassesSemanticDbTests extends TestSuite {

  val scala3Version = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)

  object CompiledClassesSemanticDb extends TestRootModule {
    object main extends ScalaModule {
      def scalaVersion = scala3Version
    }

    object test extends ScalaModule {
      def scalaVersion = scala3Version
      def moduleDeps = Seq(main)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "compiled-classes-semanticdb"

  def tests: Tests = Tests {
    test("compiledClassesAndSemanticDbFilesIncludesTransitiveDeps") - UnitTester(
      CompiledClassesSemanticDb,
      sourceRoot = resourcePath
    ).scoped { eval =>
      val Right(result) =
        eval.apply(CompiledClassesSemanticDb.test.compiledClassesAndSemanticDbFiles).runtimeChecked

      val outFolder = eval.outPath
      val files = os.walk(outFolder).map(_.relativeTo(outFolder))

      // Check that both main and test module files are present
      val expected = Seq(
        os.rel / "main" / "compiledClassesAndSemanticDbFiles.dest" / "app" / "Hello.class",
        os.rel / "main" / "compiledClassesAndSemanticDbFiles.dest" / "app" / "Hello.tasty",
        os.rel / "test" / "compiledClassesAndSemanticDbFiles.dest" / "app" / "tests" / "MyTest.class",
        os.rel / "test" / "compiledClassesAndSemanticDbFiles.dest" / "app" / "tests" / "MyTest.tasty"
      )

      val missing = expected.filterNot(files.contains)
      assert(missing.isEmpty)
    }
  }
}
