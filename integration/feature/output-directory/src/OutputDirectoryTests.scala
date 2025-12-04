package mill.integration

import mill.constants.EnvVars
import mill.constants.OutFiles.OutFiles
import mill.testkit.UtestIntegrationTestSuite
import utest._

object OutputDirectoryTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("Output directory sanity check") - integrationTest { tester =>
      import tester._
      eval("__.compile").isSuccess ==> true
      val defaultOutDir = workspacePath / OutFiles.defaultOut
      assert(os.isDir(defaultOutDir))
    }

    test("Output directory elsewhere in workspace") - integrationTest { tester =>
      import tester._
      eval(
        "__.compile",
        env = Map(EnvVars.MILL_OUTPUT_DIR -> "testing/test-out")
      ).isSuccess ==> true
      val expectedOutDir = workspacePath / "testing/test-out"
      val defaultOutDir = workspacePath / OutFiles.defaultOut
      assert(os.isDir(expectedOutDir))
      assert(!os.exists(defaultOutDir))
    }

    test("Output directory outside workspace") - integrationTest { tester =>
      import tester._
      val outDir = os.temp.dir() / "tmp-out"
      eval(
        "__.compile",
        env = Map(EnvVars.MILL_OUTPUT_DIR -> outDir.toString)
      ).isSuccess ==> true
      val defaultOutDir = workspacePath / OutFiles.defaultOut
      assert(os.isDir(outDir))
      assert(!os.exists(defaultOutDir))
    }
  }
}
