package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("Mill init works") - integrationTest { tester =>
      import tester._
      eval(("init", "com-lihaoyi/mill-scala-hello.g8", "--name=example")).isSuccess ==> true
      val projFile = workspacePath / "example/build.sc"
      assert(os.exists(projFile))
    }
  }
}
