package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("Mill init works") - integrationTest { tester =>
      import tester._
      val res = eval("init")
      res.isSuccess ==> true

      val exampleListOut = out("init")
      val parsed = exampleListOut.json.arr.map(_.str)

      val firstProj = parsed.head
      val downloaded = eval(("init", firstProj))
      println("downloaded.out: |" +downloaded.out.toString)
      println("downloaded.err: |" +downloaded.err.toString)

      downloaded.isSuccess ==> true

      val outDir = out("init")
      val parsedOutDir = outDir.json.arr.map(_.str).head

      val downloadedParentDir = os.Path(parsedOutDir)

      val paths = os.walk(path = downloadedParentDir, maxDepth = 1)
      val extractedDir = paths.find(_.last.endsWith(firstProj.replace("/", "-")))

      assert(extractedDir.isDefined)

      assert(os.exists(extractedDir.get / "build.mill"))
    }
  }
}
//integration.feature[init].fork.testCached
//integration.feature[init].local.testCached
//integration.feature[init].server.testCached
//integration.feature[init].test.testCached
