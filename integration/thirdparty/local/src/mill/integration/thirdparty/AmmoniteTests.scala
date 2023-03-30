package mill.integration.thirdparty

import utest._

import scala.util.Properties

class AmmoniteTests(fork: Boolean)
    extends IntegrationTestSuite("MILL_AMMONITE_REPO", "ammonite", fork) {
  val tests = Tests {
    initWorkspace()

    def check(scalaVersion: String) = {
      val tccl = Thread.currentThread().getContextClassLoader()
      try {
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader())
        val replTests = eval(
          s"amm.repl[$scalaVersion].test",
          "{ammonite.unit,ammonite.session.ProjectTests.guava}"
        )
        val replTestMeta = meta(s"amm.repl[$scalaVersion].test.test")
        assert(
          replTests,
          replTestMeta.contains("ammonite.session.ProjectTests.guava"),
          replTestMeta.contains("ammonite.unit.SourceTests.objectInfo.thirdPartyJava")
        )

        val compileResult = eval(
          s"{shell,sshd,amm,integration}[$scalaVersion].test.compile"
        )

        assert(
          compileResult,
          os.walk(workspacePath / "out" / "integration" / scalaVersion / "test" / "compile.dest")
            .exists(_.last == "ErrorTruncationTests.class")
        )
      } finally {
        Thread.currentThread().setContextClassLoader(tccl)
      }
    }

    "scala2126" - {
      if (Properties.isJavaAtLeast(17)) "Scala 2.12 tests don't support Java 17+"
      else check("2.12.6")
    }

  }
}
