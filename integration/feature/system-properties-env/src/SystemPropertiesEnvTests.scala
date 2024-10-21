package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

// Ensure that properties and environment can be correctly added
// and removed despite consecutive runs all running in the same JVM
object SystemPropertiesEnvTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("properties") - integrationTest { tester =>
      val result1 = tester.eval(("show", "properties"))
      assert(result1.isSuccess == true)
      result1.out ==> "[]"

      val result2 = tester.eval(("-Dmy-prop=hello", "show", "properties"))
      assert(result2.isSuccess == true)
      result2.out ==> "[\n  \"hello\"\n]"

      val result3 = tester.eval(("show", "properties"))
      assert(result3.isSuccess == true)
      result3.out ==> "[]"

      val result4 = tester.eval(("-Dmy-prop=hello", "show", "properties"))
      assert(result4.isSuccess == true)
      result4.out ==> "[\n  \"hello\"\n]"

      val result5 = tester.eval(("show", "properties"))
      assert(result5.isSuccess == true)
      result5.out ==> "[]"
    }
    test("environment") - integrationTest { tester =>
      val result1 = tester.eval(("show", "environment"))
      assert(result1.isSuccess == true)
      result1.out ==> "[]"

      val result2 = tester.eval(("show", "environment"), env = Map("MY_ENV" -> "HELLO"))
      assert(result2.isSuccess == true)
      result2.out ==> "[\n  \"HELLO\"\n]"

      val result3 = tester.eval(("show", "environment"))
      assert(result3.isSuccess == true)
      result3.out ==> "[]"

      val result4 = tester.eval(("show", "environment"), env = Map("MY_ENV" -> "HELLO"))
      assert(result4.isSuccess == true)
      result4.out ==> "[\n  \"HELLO\"\n]"

      val result5 = tester.eval(("show", "environment"))
      assert(result5.isSuccess == true)
      result5.out ==> "[]"
    }
  }
}
