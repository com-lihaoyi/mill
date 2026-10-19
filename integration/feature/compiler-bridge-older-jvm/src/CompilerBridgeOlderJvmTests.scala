package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object CompilerBridgeOlderJvmTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("compile") - integrationTest { tester =>
      import tester.*

      // The bridge can't be compiled without `-release` on Mill's JVM: this must
      // be reported, rather than leave behind an empty bridge marked as compiled
      val fooRes = eval("foo.compile")
      assert(!fooRes.isSuccess)
      assert(fooRes.err.contains("Failed to compile the compiler bridge for Scala 2.13.2"))

      // Compiling the bridge for an older JVM passes `-release` to scalac
      val barRes = eval("bar.compile")
      assert(barRes.isSuccess)
    }
  }
}
