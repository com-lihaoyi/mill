package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object Scala3SyntaxTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res0 = eval("day[Sunday].today")
      assert(res0.isSuccess)
      assert(res0.out == "Today is Sunday")

      val res1 = eval(("anyDay", "--myDay", "Tuesday"))
      assert(res1.isSuccess)
      assert(res1.out == "Today is Tuesday")

      val res2 = eval("someTopLevelCommand")
      assert(res2.isSuccess)
      assert(res2.out == "Hello, world! Box[Int] 42")
      val res3 = eval(("show", "namedTupleTask"))
      assert(res3.isSuccess)
      assert(
        ujson.read(res3.out) ==
          ujson.read("""{"hello": "world", "i": ["am", "cow"]}""")
      )
    }
  }
}
