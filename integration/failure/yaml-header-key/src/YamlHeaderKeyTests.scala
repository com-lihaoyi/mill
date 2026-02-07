package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderKeyTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval(("resolve", "_"))

      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] build.mill:1:5",
        "//| invalidKey: lols",
        "    ^"
      )
      assert(res.err.contains("key \"invalidKey\" does not override any task"))

      res.assertContainsLines(
        "[error] build.mill:2:5",
        "//| mvnDep: lols",
        "    ^"
      )
      assert(
        res.err.contains("key \"mvnDep\" does not override any task, did you mean \"mvnDeps\"?")
      )

      res.assertContainsLines(
        "[error] build.mill:3:5",
        "//| mill-jm-version: lols",
        "    ^"
      )
      assert(res.err.contains(
        "key \"mill-jm-version\" does not override any task, did you mean \"mill-jvm-version\"?"
      ))
      assert(res.err.linesIterator.toList.length < 30)
    }
  }
}
