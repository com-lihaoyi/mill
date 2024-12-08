package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

class LauncherOldVersionsTests(version: String) extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val launcherEnv =
        if (mill.main.client.Util.isWindows) "MILL_LAUNCHER_BAT"
        else "MILL_LAUNCHER"

      val workspacePath = tester.workspacePath

      val launcherScript = sys.env(launcherEnv)
      os.write.over(workspacePath / ".mill-version", version)
      val res = os.call(cmd = (launcherScript, "version"), cwd = workspacePath, stderr = os.Pipe)
      val outText = res.out.text().trim
      val errText = res.err.text().trim
      pprint.log(outText)
      pprint.log(errText)
      assert(outText == version)
    }
  }
}

// Split these into separate test groups so they can run in parallel
//
// Older versions of Mill do not work on latest macbook pros
//object LauncherVersionTests_0_1 extends LauncherOldVersionsTests("0.1.7")
//object LauncherVersionTests_0_2 extends LauncherOldVersionsTests("0.2.7")
//object LauncherVersionTests_0_3 extends LauncherOldVersionsTests("0.3.6")
//object LauncherVersionTests_0_4 extends LauncherOldVersionsTests("0.4.2")
//object LauncherVersionTests_0_5 extends LauncherOldVersionsTests("0.5.9")
//object LauncherVersionTests_0_6 extends LauncherOldVersionsTests("0.6.3")
//object LauncherVersionTests_0_7 extends LauncherOldVersionsTests("0.7.4")
//object LauncherVersionTests_0_8 extends LauncherOldVersionsTests("0.8.0")
object LauncherVersionTests_0_9 extends LauncherOldVersionsTests("0.9.12")
object LauncherVersionTests_0_10 extends LauncherOldVersionsTests("0.10.15")
object LauncherVersionTests_0_11 extends LauncherOldVersionsTests("0.11.13")
