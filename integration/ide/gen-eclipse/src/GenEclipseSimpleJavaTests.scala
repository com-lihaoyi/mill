package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import GenEclipseUtils._
import os.Path
import utest.{Tests, test}

object GenEclipseSimpleJavaTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "simple-java-project"

  def tests: Tests = Tests {
    test("Simple Java, Junit4 project") - integrationTest { tester =>
      import tester._

      val ret = eval("mill.eclipse/", check = true)
      assert(ret.exitCode == 0)

      checkOrgEclipseCoreResourcesPrefs(workspacePath)
      checkOrgEclipseJdtCorePrefs(workspacePath)
      checkProjectFile(workspacePath, Seq.empty[String])
      checkClasspathFile(
        workspacePath,
        "src",
        Seq.empty[String],
        Seq("test/src"),
        Seq.empty[String],
        Seq("junit")
      )
    }
  }
}
