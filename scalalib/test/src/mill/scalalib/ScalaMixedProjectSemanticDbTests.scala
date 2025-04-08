package mill.scalalib

import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import mill.define.Discover
import mill.util.TokenReaders._

object ScalaMixedProjectSemanticDbTests extends TestSuite {

  object SemanticWorld extends TestBaseModule {
    object core extends HelloWorldTests.SemanticModule

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-mixed"

  def tests: Tests = Tests {

    test("semanticDbData") {
      def semanticDbFiles: Set[os.SubPath] = Set(
        os.sub / "META-INF/semanticdb/core/src/Foo.scala.semanticdb",
        os.sub / "META-INF/semanticdb/core/src/JFoo.java.semanticdb"
      )

      test("fromScratch") - UnitTester(SemanticWorld, sourceRoot = resourcePath).scoped { eval =>
        {
          println("first - expected full compile")
          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked

          val dataPath = eval.outPath / "core/semanticDbData.dest/data"
          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))

          val expectedSemFiles = semanticDbFiles
          assert(
            result.value.path == dataPath,
            outputFiles.nonEmpty,
            outputFiles.toSet == expectedSemFiles,
            result.evalCount > 0,
            os.exists(dataPath / os.up / "zinc")
          )
        }
        {
          println("second - expected no compile")
          // don't recompile if nothing changed
          val Right(result2) = eval.apply(SemanticWorld.core.semanticDbData): @unchecked
          assert(result2.evalCount == 0)
        }
      }
    }
  }
}
