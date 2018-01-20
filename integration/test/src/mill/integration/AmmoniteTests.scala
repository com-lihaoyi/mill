package mill.integration

import ammonite.ops._
import utest._

object AmmoniteTests extends IntegrationTestSuite("MILL_AMMONITE_REPO", "ammonite") {
  val tests = Tests{
    initWorkspace()

    def check(scalaVersion: String) = {
      val replTests = eval(
        s"amm.repl[$scalaVersion].test", "{ammonite.unit,ammonite.session.ProjectTests.guava}"
      )
      val replTestMeta = meta(s"amm.repl[$scalaVersion].test.test")
      assert(
        replTests,
        replTestMeta.contains("ammonite.session.ProjectTests.guava"),
        replTestMeta.contains("ammonite.unit.SourceTests.objectInfo.thirdPartyJava")
      )

      val compileResult = eval(
        "--all", s"{shell,sshd,amm,integration}[$scalaVersion].test.compile"
      )

      assert(
        compileResult,
        ls.rec(workspacePath / 'out / 'integration / scalaVersion / 'test / 'compile)
          .exists(_.name == "ErrorTruncationTests.class")
      )
    }

    'scala2118 - check("2.11.8")
    'scala2124 - check("2.12.4")

  }
}
