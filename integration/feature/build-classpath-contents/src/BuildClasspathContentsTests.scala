package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.define.WorkspaceRoot.workspaceRoot
import utest._

object BuildClasspathContentsTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      val result1 =
        tester.eval(("--meta-level", "1", "show", "compileClasspath"), stderr = os.Inherit)
      val deserialized = upickle.default.read[Seq[mill.define.PathRef]](result1.out)
      val millPublishedJars = deserialized
        .map(_.path.last)
        .filter(_.startsWith("mill-"))
        .sorted
      val millLocalClasspath = deserialized
        .map(_.path)
        .filter(_.startsWith(workspaceRoot))
        .map(_.subRelativeTo(workspaceRoot))
        .filter(!_.startsWith("out/integration"))
        .map(_.toString)
        .sorted
      if (sys.env("MILL_INTEGRATION_IS_PACKAGED_LAUNCHER") == "true") {

        val expected =  List(
          "mill-core-api_3.jar",
          "mill-core-constants.jar",
          "mill-core-define_3.jar",
          "mill-core-util_3.jar",
          "mill-libs-androidlib_3.jar",
          "mill-libs-idea_3.jar",
          "mill-libs-init_3.jar",
          "mill-libs-javascriptlib_3.jar",
          "mill-libs-kotlinlib-worker_3.jar",
          "mill-libs-kotlinlib_3.jar",
          "mill-libs-main_3.jar",
          "mill-libs-pythonlib_3.jar",
          "mill-libs-scalajslib-worker-api_3.jar",
          "mill-libs-scalajslib_3.jar",
          "mill-libs-scalalib-api_3.jar",
          "mill-libs-scalalib_3.jar",
          "mill-libs-scalanativelib-worker-api_3.jar",
          "mill-libs-scalanativelib_3.jar",
          "mill-libs-testrunner-entrypoint.jar",
          "mill-libs-testrunner_3.jar",
          "mill-libs_3.jar",
          "mill-moduledefs_3-0.11.3-M5.jar",
          "mill-runner-bsp_3.jar"
        )

        assert(millPublishedJars == expected)
        assert(millLocalClasspath == Nil)
      } else {
        sys.error("This test must be run in `packaged` mode, not `local`")
      }
    }
  }
}
