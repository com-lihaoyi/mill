package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtFs2Tests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      // sbt 1.10.11
      "https://github.com/typelevel/fs2.git",
      "v3.12.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      """Requires manual fix for scalacOptions Seq("-release", "8").
        |
        |benchmark[2.13.16].compile
        |[error] .../fs2/benchmark/src/main/scala/fs2/benchmark/FlowInteropBenchmark.scala:39:29: object Flow is not a member of package java.util.concurrent
        |""".stripMargin
    }
  }
}
