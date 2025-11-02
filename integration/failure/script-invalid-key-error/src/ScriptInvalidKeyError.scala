package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptInvalidKeyError extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("folder/Foo.java")
      assert(res.err.contains("invalid build config `folder/Foo.java` key does not override any task: \"moduleDep\""))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)

      val res2 = tester.eval("folder/Foo.scala")
      assert(res2.err.contains("invalid build config `folder/Foo.scala` key does not override any task: \"moduleDep\""))
      assert(res2.err.linesIterator.toList.length < 20)

      val res3 = tester.eval("folder/Foo.kt")
      assert(res3.err.contains("invalid build config `folder/Foo.kt` key does not override any task: \"moduleDep\""))
      assert(res3.err.linesIterator.toList.length < 20)
    }
  }
}
