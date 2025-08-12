package mill.integration

import mill.constants.OutFiles
import mill.testkit.*
import os.RelPath
import utest.*

object CompiledClassesAndSemanticDbFilesDependencyMergingTests extends UtestIntegrationTestSuite {
  override def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("test.compiledClassesAndSemanticDbFiles")
      assert(res.isSuccess)

      val taskDest =
        workspacePath / OutFiles.out / "test" / "compiledClassesAndSemanticDbFiles.dest"
      val files = os.walk(taskDest).map(_.relativeTo(taskDest))

      val expected = Seq(
        RelPath("app") / "Hello.class",
        RelPath("app") / "Hello.tasty",
        RelPath("app") / "tests" / "MyTest.class",
        RelPath("app") / "tests" / "MyTest.tasty"
      )
      val missing = expected.filterNot(files.contains)
      assert(missing.isEmpty)
    }
  }
}
