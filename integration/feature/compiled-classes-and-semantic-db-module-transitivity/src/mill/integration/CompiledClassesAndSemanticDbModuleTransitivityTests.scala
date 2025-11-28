package mill.integration

import mill.constants.OutFiles.OutFiles
import mill.testkit.*
import os.RelPath
import utest.*

object CompiledClassesAndSemanticDbModuleTransitivityTests extends UtestIntegrationTestSuite {
  override def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("test.compiledClassesAndSemanticDbFiles")
      assert(res.isSuccess)

      val mainDest = RelPath("main") / "compiledClassesAndSemanticDbFiles.dest"
      val testDest = RelPath("test") / "compiledClassesAndSemanticDbFiles.dest"

      val expected = Seq(
        mainDest / "app" / "Hello.class",
        mainDest / "app" / "Hello.tasty",
        testDest / "app" / "tests" / "MyTest.class",
        testDest / "app" / "tests" / "MyTest.tasty"
      )

      val outFolder = workspacePath / OutFiles.out
      val files = os.walk(outFolder).map(_.relativeTo(outFolder))
      val missing = expected.filterNot(files.contains)
      assert(missing.isEmpty)
    }
  }
}
