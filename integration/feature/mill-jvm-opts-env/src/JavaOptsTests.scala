package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object JavaOptsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("propagateJavaOpts") - integrationTest { tester =>
      import tester.*

      val n = 2L
      val maxMemory = eval(("show", "maxMemory"), Map("JAVA_OPTS" -> s"-Xmx${n}G"))
      assert(maxMemory.isSuccess)
      assert(maxMemory.out.trim.toLong == n * 1024 * 1024 * 1024)
    }

    test("propagateJavaOptsProps") - integrationTest { tester =>
      import tester.*

      val propValue = 123
      val testProperty =
        eval(("show", "testProperty"), Map("JAVA_OPTS" -> s"-Dtest.property=$propValue"))
      assert(testProperty.isSuccess)
      assert(testProperty.out.trim.toInt == propValue)
    }
  }
}
