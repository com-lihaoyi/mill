package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("Mill init works") - integrationTest { tester =>
      import tester._
      val res = eval("init")
      res.isSuccess ==> true
      val firstProj = res.out.split("\n")(1)
      val downloaded = eval(("init",firstProj))
      downloaded.isSuccess ==> true
      val paths = os.walk(path = workspacePath,maxDepth = 1)
      val extractedDir = paths.find(_.last.endsWith(firstProj.replace("/","-")))
      extractedDir.isDefined ==> true
      assert(os.exists(extractedDir.get / "build.mill"))
    }
  }
}
