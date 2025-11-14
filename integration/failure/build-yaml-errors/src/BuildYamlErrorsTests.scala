package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

// Basic tests for errors in build.mill.yaml an package.mill.yaml files. The errors don't
// look great, but for now just assert on them and make sure they don't regress
object BuildYamlErrorsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      val res = tester.eval("version")
      assert(res.err.replace('\\', '/').contains("mispelledextends/package.mill.yaml"))
      assert(res.err.contains("trait package_ extends mill.javalib.JavaModuleTypod"))
      assert(res.err.contains("type JavaModuleTypod is not a member of mill.javalib"))

      os.remove.all(tester.workspacePath / "mispelledextends")

      val res2 = tester.eval("version")
      assert(res2.err.replace('\\', '/').contains("invalidtaskname/package.mill.yaml"))
//      assert(res2.err.contains("override def mvnDepsTypo = Task.Stub()"))
//      assert(res2.err.contains("method mvnDepsTypo overrides nothing"))
    }
  }
}
