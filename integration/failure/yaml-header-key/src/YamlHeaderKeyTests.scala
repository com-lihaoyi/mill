package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlHeaderKeyTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      // Check for dotty-style error format with file:line:col, code snippet, and pointer
      assert(res.err.contains("mill-build/build.mill:1:17"))
      assert(res.err.contains("//| invalidKey: lols"))
      assert(res.err.contains("key \"invalidKey\" does not override any task"))

      assert(res.err.contains("mill-build/build.mill:2:9"))
      assert(res.err.contains("//| mvnDep: lols"))
      assert(
        res.err.contains("key \"mvnDep\" does not override any task, did you mean \"mvnDeps\"?")
      )

      assert(res.err.contains("mill-build/build.mill:3:14"))
      assert(res.err.contains("//| mill-jm-version: lols"))
      assert(res.err.contains(
        "key \"mill-jm-version\" does not override any task, did you mean \"mill-jvm-version\"?"
      ))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 30)
    }
  }
}
