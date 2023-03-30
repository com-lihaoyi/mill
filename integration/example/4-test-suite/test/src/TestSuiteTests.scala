package mill.integration

import utest._

import scala.util.matching.Regex

object TestSuiteTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("foo.test")
      assert(res.isSuccess)
      if (integrationTestMode != "local") {
        assert(res.out.contains("+"))
        assert(res.out.contains("foo.FooTests.hello"))
        assert(res.out.contains("Hello World"))
      }
    }
  }
}
