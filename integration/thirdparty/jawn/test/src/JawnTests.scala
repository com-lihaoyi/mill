package mill.integration
package thirdparty

import mill.util.TestUtil
import utest._

object JawnTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()

    def check(scalaVersion: String) = TestUtil.disableInJava9OrAbove("Old tests don't work") {
      val tccl = Thread.currentThread().getContextClassLoader()
      try {
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader())
        val firstCompile = eval(s"jawn[$scalaVersion].parser.test")

        assert(
          firstCompile,
          os.walk(workspacePath).exists(_.last == "AsyncParser.class"),
          os.walk(workspacePath).exists(_.last == "CharBuilderSpec.class")
        )

        for (scalaFile <- os.walk(workspacePath).filter(_.ext == "scala")) {
          os.write.append(scalaFile, "\n}")
        }

        val brokenCompile = eval(s"jawn[$scalaVersion].parser.test")

        assert(!brokenCompile)
      } finally {
        Thread.currentThread().setContextClassLoader(tccl)
      }
    }

    "scala21111" - check("2.11.11")
    "scala2123" - check("2.12.3")
  }
}
