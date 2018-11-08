package mill.integration

import utest._

class AcyclicTests(fork: Boolean)
  extends IntegrationTestSuite("MILL_ACYCLIC_REPO", "acyclic", fork) {
  val tests = Tests{
    initWorkspace()

    def check(scalaVersion: String) = {
      val firstCompile = eval(s"acyclic[$scalaVersion].compile")

      assert(
        firstCompile,
        os.walk(workspacePath).exists(_.last == "GraphAnalysis.class"),
        os.walk(workspacePath).exists(_.last == "PluginPhase.class")
      )
      for(scalaFile <- os.walk(workspacePath).filter(_.ext == "scala")){
        os.write.append(scalaFile, "\n}")
      }

      val brokenCompile = eval(s"acyclic[$scalaVersion].compile")

      assert(!brokenCompile)
    }

    'scala2118 - mill.util.TestUtil.disableInJava9OrAbove(check("2.11.8"))
    'scala2124 - check("2.12.4")

  }
}
