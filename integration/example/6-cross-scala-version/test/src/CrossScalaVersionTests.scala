package mill.integration

import utest._

import scala.util.matching.Regex

object CrossScalaVersionTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){

      val res = evalStdout("resolve", "__.run")
      assert(res.isSuccess)
      assert(
        res.out.linesIterator.toSet ==
        Set(
          "foo[2.12.17].run", "foo[2.13.10].run", "foo[3.2.2].run",
          "bar[2.12.17].run", "bar[2.13.10].run", "bar[3.2.2].run"
        )
      )

      val res2 = evalStdout("foo[2.12.17].run")
      assert(res2.isSuccess)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Foo.value: Hello World Scala library version 2.12.17"))
        assert(res2.out.contains("Specific code for Scala 2.x"))
        assert(res2.out.contains("Specific code for Scala 2.12.x"))
      }

      val res3 = evalStdout("foo[3.2.2].run")
      assert(res3.isSuccess)
      if (integrationTestMode != "local") {
        assert(res3.out.contains("Specific code for Scala 3.x"))
        assert(res3.out.contains("Specific code for Scala 3.2.x"))
      }

      val res4 = evalStdout("bar[3.2.2].run")
      assert(res4.isSuccess)
      if (integrationTestMode != "local") {
        assert(res4.out.contains("Bar.value: bar-value"))
      }
    }
  }
}
