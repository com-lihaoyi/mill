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

        val expected = List(
          "mill-bsp_3.jar",
          "mill-core-api_3.jar",
          "mill-core-constants.jar",
          "mill-core-define_3.jar",
          "mill-core-util_3.jar",
          "mill-idea_3.jar",
          "mill-javascriptlib_3.jar",
          "mill-kotlinlib-worker_3.jar",
          "mill-kotlinlib_3.jar",
          "mill-main-init_3.jar",
          "mill-main_3.jar",
          "mill-moduledefs_3-0.11.3-M5.jar",
          "mill-pythonlib_3.jar",
          "mill-scalajslib-worker-api_3.jar",
          "mill-scalajslib_3.jar",
          "mill-scalalib-api_3.jar",
          "mill-scalalib_3.jar",
          "mill-scalanativelib-worker-api_3.jar",
          "mill-scalanativelib_3.jar",
          "mill-testrunner-entrypoint.jar",
          "mill-testrunner_3.jar"
        )

        assert(millPublishedJars == expected)
        assert(millLocalClasspath == Nil)
      } else {

        // Make sure we don't include `core. exec`, `core.resolve`, `core`, `runner`, `runner.server`,
        // etc. since users should not need to write code that compiles against those interfaces
        val expected: Seq[String] = List(
          "out/bsp/buildInfoResources.dest",
          "out/bsp/compile.dest/classes",
          "out/core/api/buildInfoResources.dest",
          "out/core/api/compile.dest/classes",
          "out/core/constants/buildInfoResources.dest",
          "out/core/constants/compile.dest/classes",
          "out/core/define/compile.dest/classes",
          "out/core/util/buildInfoResources.dest",
          "out/core/util/compile.dest/classes",
          "out/dist/localTestOverridesClasspath.dest",
          "out/idea/compile.dest/classes",
          "out/javascriptlib/compile.dest/classes",
          "out/kotlinlib/buildInfoResources.dest",
          "out/kotlinlib/compile.dest/classes",
          "out/kotlinlib/worker/compile.dest/classes",
          "out/main/compile.dest/classes",
          "out/main/init/compile.dest/classes",
          "out/main/init/exampleList.dest",
          "out/pythonlib/compile.dest/classes",
          "out/scalajslib/buildInfoResources.dest",
          "out/scalajslib/compile.dest/classes",
          "out/scalajslib/worker-api/compile.dest/classes",
          "out/scalalib/api/buildInfoResources.dest",
          "out/scalalib/api/compile.dest/classes",
          "out/scalalib/compile.dest/classes",
          "out/scalanativelib/compile.dest/classes",
          "out/scalanativelib/worker-api/compile.dest/classes",
          "out/testrunner/compile.dest/classes",
          "out/testrunner/entrypoint/compile.dest/classes",
          "scalalib/resources"
        )

        assert(millLocalClasspath == expected)
        assert(millPublishedJars == Seq("mill-moduledefs_3-0.11.3-M5.jar"))
      }
    }
  }
}
