package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtScalaLoggingTests extends GitRepoIntegrationTestSuite {

  // sbt 1.6.2
  // cross Scala versions 2.11.12 2.12.15 2.13.8 3.1.2
  // single root module
  def gitRepoUrl = "https://github.com/lightbend-labs/scala-logging.git"
  def gitRepoBranch = "v3.9.5"

  def tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval(("resolve", "_"), stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.test", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
    }
  }
}
