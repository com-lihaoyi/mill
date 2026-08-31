package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import os.Path
import utest.*

object GenIdeaAndroidTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "android"
  override def allowSharedOutputDir: Boolean = false

  def tests: Tests = Tests {
    test("android idea generation uses distinct override paths") - integrationTest { tester =>
      import tester.*

      val androidHome = Seq(
        sys.env.get("ANDROID_HOME").map(os.Path(_, os.pwd)),
        sys.env.get("ANDROID_SDK_ROOT").map(os.Path(_, os.pwd)),
        Some(os.home / "Library" / "Android" / "sdk"),
        Some(os.home / "Android" / "Sdk"),
        Some(os.Path("/opt/android-sdk"))
      ).flatten.find(os.exists).getOrElse(sys.error("Android SDK is required for this test"))

      eval(
        "mill.idea/",
        env = Map("ANDROID_HOME" -> androidHome.toString),
        check = true,
        timeout = 120000
      )

      val ideaTasks = workspacePath / "out" / "app" / "internalGenIdea"
      assert(
        os.exists(workspacePath / ".idea" / "modules.xml"),
        os.exists(ideaTasks / "extDependencies.json"),
        os.exists(
          ideaTasks / "extDependencies.super" / "GenIdeaAndroidModule.json"
        ),
        os.exists(ideaTasks / "moduleGeneratedSources.json"),
        os.exists(
          ideaTasks / "moduleGeneratedSources.super" / "GenIdeaAndroidModule.json"
        ),
        !os.exists(ideaTasks / "androidExtDependencies.json"),
        !os.exists(ideaTasks / "androidModuleGeneratedSources.json")
      )
    }
  }
}
