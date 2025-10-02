import mill.api.BuildCtx
import mill.testkit.UtestIntegrationTestSuite
import utest.*

object BuildClasspathContentsTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      val result1 =
        tester.eval(("--meta-level", "1", "show", "compileClasspath"), stderr = os.Inherit)
      val deserialized = upickle.read[Seq[mill.api.PathRef]](result1.out)
      val millPublishedJars = deserialized
        .map(_.path.last)
        .filter(_.startsWith("mill-"))
        .sorted
      val millLocalClasspath = deserialized
        .map(_.path)
        .filter(_.startsWith(BuildCtx.workspaceRoot))
        .map(_.subRelativeTo(BuildCtx.workspaceRoot))
        .filter(!_.startsWith("out/integration"))
        .map(_.toString)
        .sorted
      if (sys.env("MILL_INTEGRATION_IS_PACKAGED_LAUNCHER") == "true") {
        assertGoldenLiteral(
          millPublishedJars,
          List(
            "mill-core-api-daemon_3.jar",
            "mill-core-api_3.jar",
            "mill-core-constants.jar",
            "mill-libs-androidlib-databinding_3.jar",
            "mill-libs-androidlib_3.jar",
            "mill-libs-daemon-client.jar",
            "mill-libs-daemon-server_3.jar",
            "mill-libs-javalib-api_3.jar",
            "mill-libs-javalib-testrunner-entrypoint.jar",
            "mill-libs-javalib-testrunner_3.jar",
            "mill-libs-javalib_3.jar",
            "mill-libs-javascriptlib_3.jar",
            "mill-libs-kotlinlib-api_3.jar",
            "mill-libs-kotlinlib-ksp2-api_3.jar",
            "mill-libs-kotlinlib_3.jar",
            "mill-libs-pythonlib_3.jar",
            "mill-libs-rpc_3.jar",
            "mill-libs-scalajslib-api_3.jar",
            "mill-libs-scalajslib_3.jar",
            "mill-libs-scalalib_3.jar",
            "mill-libs-scalanativelib-api_3.jar",
            "mill-libs-scalanativelib_3.jar",
            "mill-libs-simple_3.jar",
            "mill-libs-util_3.jar",
            "mill-libs_3.jar",
            "mill-moduledefs_3-0.11.10.jar"
          )
        )
        assert(millLocalClasspath == Nil)
      } else {
        sys.error("This test must be run in `packaged` mode, not `local`")
      }
    }
  }
}
