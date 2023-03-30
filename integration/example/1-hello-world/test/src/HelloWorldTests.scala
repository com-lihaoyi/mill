package mill.integration

import utest._

import scala.util.matching.Regex

object HelloWorldTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("resolve", "_.run")
      assert(res.out.linesIterator.toSet == Set("foo.run"))

      val res2 = evalStdout("foo.run")
      assert(res2.isSuccess)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Foo.value: <h1>hello</h1>"))
      }
    }
  }
}
