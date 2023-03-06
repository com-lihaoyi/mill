package mill.integration.thirdparty

import utest._

class AcyclicTests(fork: Boolean)
    extends IntegrationTestSuite("MILL_ACYCLIC_REPO", "acyclic", fork) {
  val tests = Tests {
    initWorkspace()

    def check(scalaVersion: String) = {
      val tccl = Thread.currentThread().getContextClassLoader()
      try {
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader())

        val firstCompile = eval(s"acyclic[$scalaVersion].compile")

        assert(
          firstCompile,
          os.walk(workspacePath).exists(_.last == "GraphAnalysis.class"),
          os.walk(workspacePath).exists(_.last == "PluginPhase.class")
        )
        for (scalaFile <- os.walk(workspacePath).filter(_.ext == "scala")) {
          os.write.append(scalaFile, "\n}")
        }

        val brokenCompile = eval(s"acyclic[$scalaVersion].compile")

        assert(!brokenCompile)
      } finally {
        Thread.currentThread().setContextClassLoader(tccl)
      }
    }

    "scala2118" - mill.util.TestUtil.disableInJava9OrAbove("Scala 2.11 not supported")(
      check("2.11.8")
    )
    "scala2125" - check("2.12.5")

  }
}
