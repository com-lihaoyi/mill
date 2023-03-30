package mill.integration

import utest._

import scala.util.matching.Regex

object ScalaCombinedTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("resolve", "__.run")
      assert(
        res.out.linesIterator.toSet ==
        Set(
          "bar[2.13.10].run",
          "bar[2.13.10].test.run",
          "bar[3.2.2].run",
          "bar[3.2.2].test.run",
          "foo[2.13.10].run",
          "foo[2.13.10].test.run",
          "foo[3.2.2].run",
          "foo[3.2.2].test.run",
          "qux.run"
        )
      )

      val res2 = evalStdout("foo[2.13.10].run")
      assert(res2.isSuccess)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Foo.value: <h1>hello</h1>"))
        assert(res2.out.contains("Bar.value: <p>world Specific code for Scala 2.x</p>"))
        assert(res2.out.contains("Qux.value: 31337"))
      }

      val res3 = evalStdout("bar[3.2.2].test")
      assert(res3.isSuccess)
      if (integrationTestMode != "local") {
        assert(res3.out.contains("bar.BarTests.test"))
        assert(res3.out.contains("<p>world Specific code for Scala 3.x</p>"))
      }

      val res4 = evalStdout("qux.run")
      assert(res4.isSuccess)
      if (integrationTestMode != "local") {
        assert(res4.out.contains("Qux.value: 31337"))
      }
    }
  }
}
