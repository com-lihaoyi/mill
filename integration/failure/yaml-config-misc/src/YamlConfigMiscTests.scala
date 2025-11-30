package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

// Basic tests for errors in build.mill.yaml an package.mill.yaml files. The errors don't
// look great, but for now just assert on them and make sure they don't regress
object YamlConfigMiscTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      val res = tester.eval("version")
      assert(res.err.replace('\\', '/').contains("mispelledextends/package.mill.yaml"))
      assert(res.err.contains("trait package_ extends mill.javalib.JavaModuleTypod"))
      assert(res.err.contains("type JavaModuleTypod is not a member of mill.javalib"))

      tester.modifyFile(
        tester.workspacePath / "mispelledextends/package.mill.yaml",
        _ => "mill-version: 1.0.0\nextends: [mill.javalib.JavaModule]"
      )

      val res2 = tester.eval("mispelledextends.compile")
      // mispelledextends/package.mill.yaml:1:15
      // mill-version: 1.0.0
      //               ^
      assert(res2.err.replace('\\', '/').contains("mispelledextends/package.mill.yaml:1:15"))
      assert(res2.err.contains("mill-version: 1.0.0"))
      assert(res2.err.contains("              ^"))
      assert(res2.err.contains(
        "key \"mill-version\" can only be used in your root `build.mill` or `build.mill.yaml` file"
      ))
    }
  }
}
