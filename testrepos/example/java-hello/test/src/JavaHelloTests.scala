package mill.integration

import utest._

import scala.util.matching.Regex

object JavaHelloTests extends IntegrationTestSuite.Cross {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success"){
      val res = evalStdout("resolve", "_.run")
      assert(res.out.linesIterator.toSet == Set("foo.run", "bar.run"))

      val res2 = evalStdout("foo.run")
      assert(res2.isSuccess)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Foo.value: 31337"))
        assert(res2.out.contains("Bar.value: 271828"))
      }
    }
  }
}
