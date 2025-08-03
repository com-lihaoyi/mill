package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import GenEclipseUtils._
import os.Path
import utest.{Tests, test}

object GenEclipseMultiGeneratedJavaTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path =
    super.workspaceSourcePath / "multi-generated-java-project"

  def tests: Tests = Tests {
    test("Multi module project with generated sources") - integrationTest { tester =>
      import tester._

      val ret = eval("mill.eclipse/", check = true)
      assert(ret.exitCode == 0)
      assert(ret.out.contains("Name: base"))
      assert(ret.out.contains("Name: runner"))

      // Check the base project only containing the generated sources
      val baseProjectPath = workspacePath / "base"
      checkOrgEclipseCoreResourcesPrefs(baseProjectPath)
      checkOrgEclipseJdtCorePrefs(baseProjectPath)
      checkProjectFile(baseProjectPath, Seq("generatedSources.dest"))
      checkClasspathFile(
        baseProjectPath,
        null,
        Seq("generatedSources.dest"),
        Seq.empty[String],
        Seq.empty[String],
        Seq.empty[String]
      )

      // Check the runner project relying on the base project
      val runnerProjectPath = workspacePath / "runner"
      checkOrgEclipseCoreResourcesPrefs(runnerProjectPath)
      checkOrgEclipseJdtCorePrefs(runnerProjectPath)
      checkProjectFile(runnerProjectPath, Seq.empty[String])
      checkClasspathFile(
        runnerProjectPath,
        "src",
        Seq.empty[String],
        Seq.empty[String],
        Seq("base"),
        Seq.empty[String]
      )
    }
  }
}
