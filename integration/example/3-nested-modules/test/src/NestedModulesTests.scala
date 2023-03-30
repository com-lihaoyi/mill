package mill.integration

import utest._

import scala.util.matching.Regex

object NestedModulesTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("resolve", "__.run")
      assert(
        res.out.linesIterator.toSet ==
        Set("wrapper.foo.run", "wrapper.bar.run", "qux.run")
      )

      val res2 = evalStdout("qux.run")
      assert(res2.isSuccess)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Foo.value: <h1>hello</h1>"))
        assert(res2.out.contains("Bar.value: <p>world</p>"))
        assert(res2.out.contains("Qux.value: <p>today</p>"))
      }
    }
  }
}
