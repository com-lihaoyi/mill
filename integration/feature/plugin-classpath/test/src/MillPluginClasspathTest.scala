package mill.integration

import utest._

object MillPluginClasspathTest extends IntegrationTestSuite {
  initWorkspace()

  val embeddedModules: Seq[(String, String)] = Seq(
    ("com.lihaoyi", "mill-main-client"),
    ("com.lihaoyi", "mill-main-api_2.13"),
    ("com.lihaoyi", "mill-main-util_2.13"),
    ("com.lihaoyi", "mill-main-codesig_2.13"),
    ("com.lihaoyi", "mill-runner-linenumbers_2.13"),
    ("com.lihaoyi", "mill-bsp_2.13"),
    ("com.lihaoyi", "mill-scalanativelib-worker-api_2.13"),
    ("com.lihaoyi", "mill-testrunner-entrypoint"),
    ("com.lihaoyi", "mill-scalalib-api_2.13"),
    ("com.lihaoyi", "mill-testrunner_2.13"),
    ("com.lihaoyi", "mill-main-define_2.13"),
    ("com.lihaoyi", "mill-main-resolve_2.13"),
    ("com.lihaoyi", "mill-main-eval_2.13"),
    ("com.lihaoyi", "mill-main_2.13"),
    ("com.lihaoyi", "mill-scalalib_2.13"),
    ("com.lihaoyi", "mill-scalanativelib_2.13"),
    ("com.lihaoyi", "mill-scalajslib-worker-api_2.13"),
    ("com.lihaoyi", "mill-scalajslib_2.13"),
    ("com.lihaoyi", "mill-runner_2.13"),
    ("com.lihaoyi", "mill-idea_2.13")
  )

  val tests: Tests = Tests {
    test("runClasspath") - {
      // We expect Mill core transitive dependencies to be filtered out
      val res1 = eval("--meta-level", "1", "runClasspath")
      assert(res1)

      val runClasspath = metaValue[Seq[String]]("mill-build.runClasspath")

      val expected = Seq("com/disneystreaming/smithy4s/smithy4s-mill-codegen-plugin_mill0.11_2.13")
      assert(expected.forall(a => runClasspath.exists(p => p.toString().contains(a))))
    }

  }
}
