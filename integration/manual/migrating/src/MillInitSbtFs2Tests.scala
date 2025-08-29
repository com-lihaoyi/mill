package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtFs2Tests extends GitRepoIntegrationTestSuite {

  // sbt 1.10.11
  // cross Scala versions 2.12.20 2.13.16 3.3.5
  // sbt-crossproject 1.3.2
  // cross partial source roots in core, io
  // .sbtopts with JVM args
  def gitRepoUrl = "https://github.com/typelevel/fs2.git"
  def gitRepoBranch = "v3.12.0"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      // requires cats-effect-testkit
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit) // fails with OOM
    }
  }
}
