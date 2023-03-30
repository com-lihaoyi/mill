package mill.integration

import utest._

import scala.util.matching.Regex

object MultiModuleTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("resolve", "_.run")
      assert(res.out.linesIterator.toSet == Set("foo.run", "bar.run"))

      val res2 = evalStdout("foo.run")
      assert(res2.isSuccess)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Foo.value: <h1>hello</h1>"))
        assert(res2.out.contains("Bar.value: <p>world</p>"))
      }

      val res3 = evalStdout("bar.run")
      assert(res3.isSuccess)
      if (integrationTestMode != "local") {
        assert(res3.out.contains("Bar.value: <p>world</p>"))
      }
    }
  }
}
