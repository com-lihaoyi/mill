package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import GenEclipseUtils._
import os.Path
import utest.{Tests, test}

object GenEclipseMavenJavaTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "maven-java-project"

  def tests: Tests = Tests {
    test("Maven Java, Junit4/5 project") - integrationTest { tester =>
      import tester._

      val ret = eval("mill.eclipse/", check = true)
      assert(ret.exitCode == 0)
      assert(ret.out.contains("Name: project"))

      // Check the Mill Build parent non-JDT project
      checkOrgEclipseCoreResourcesPrefs(workspacePath)
      checkProjectFile(workspacePath, false, Seq.empty[String])

      // Check the Maven project generated
      val generatedProjectPath = workspacePath / "project"
      checkOrgEclipseCoreResourcesPrefs(generatedProjectPath)
      checkOrgEclipseJdtCorePrefs(generatedProjectPath)
      checkProjectFile(generatedProjectPath, true, Seq.empty[String])
      checkClasspathFile(
        generatedProjectPath,
        "src/main/java",
        Seq.empty[String],
        Seq("src/test/java", "src/integration/java"),
        Seq.empty[String],
        Seq("junit", "junit-jupiter-api")
      )
    }
  }
}
