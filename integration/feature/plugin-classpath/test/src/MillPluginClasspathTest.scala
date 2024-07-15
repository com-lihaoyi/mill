package mill.integration

import utest._

object MillPluginClasspathTest extends IntegrationTestSuite {
  initWorkspace()

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
