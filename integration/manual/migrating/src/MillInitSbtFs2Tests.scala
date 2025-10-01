package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtFs2Tests extends GitRepoIntegrationTestSuite {

  // SBT 1.10.11
  // cross Scala versions 2.12.20 2.13.16 3.3.5
  // sbt-crossproject 1.3.2
  // cross partial source roots in core, io
  // compile->compile;test->test module dependencies
  // Scala version based dependencies
  // unsupported test framework munit-cats-effect
  // .sbtopts with JVM args

  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/typelevel/fs2.git",
      "v3.12.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("__.showModuleDeps"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("core.jvm[3.3.5].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(
        "core.jvm[3.3.5].publishLocal",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval(
        "reactive-streams[3.3.5].test",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval(
        "scodec.jvm[2.13.16].compile",
        stdout = os.Inherit,
        stderr = os.Inherit
      ).isSuccess ==> true
      eval("scodec.jvm[3.3.5].compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
