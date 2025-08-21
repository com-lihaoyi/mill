package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import os.Path
import utest.{Tests, test}

object GenEclipseKotlinTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "kotlin-project"

  def tests: Tests = Tests {
    test("No project generation for Kotlin projects") - integrationTest { tester =>
      import tester._

      val ret = eval("mill.eclipse/", check = true)
      assert(ret.exitCode == 0)
      assert(ret.out.contains("No Java Modules found in build, stopping here!"))
      assert(!os.exists(workspacePath / ".project"))
      assert(!os.exists(workspacePath / ".classpath"))
      assert(!os.exists(workspacePath / ".settings"))
    }
  }
}
