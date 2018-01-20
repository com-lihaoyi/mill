package mill.integration

import ammonite.ops._
import utest._

object AcyclicTests extends IntegrationTestSuite("MILL_ACYCLIC_REPO", "acyclic") {
  val tests = Tests {
    initWorkspace()

    def check(scalaVersion: String) = {
      val firstCompile = eval(s"acyclic[$scalaVersion].compile")

      assert(
        firstCompile,
        ls.rec(workspacePath).exists(_.last == "GraphAnalysis.class"),
        ls.rec(workspacePath).exists(_.last == "PluginPhase.class")
      )
      for (scalaFile <- ls.rec(workspacePath).filter(_.ext == "scala")) {
        write.append(scalaFile, "\n}")
      }

      val brokenCompile = eval(s"acyclic[$scalaVersion].compile")

      assert(!brokenCompile)
    }

    'scala2118 - check("2.11.8")
    'scala2124 - check("2.12.4")

  }
}
