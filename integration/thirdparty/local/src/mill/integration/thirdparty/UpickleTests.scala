package mill.integration.thirdparty

import utest._

import scala.util.Properties

class UpickleTests(fork: Boolean)
    extends ThirdPartyTestSuite("MILL_UPICKLE_REPO", "upickle", fork) {
  val tests = Tests {
    initWorkspace()
    "jvm21111" - {
      if (Properties.isJavaAtLeast(9)) "Scala 2.11 tests don't support Java 9+"
      else {
        assert(eval("upickleJvm[2.11.11].test"))
        val jvmMeta = meta("upickleJvm[2.11.11].test.test")
        assert(jvmMeta.contains("example.ExampleTests.simple"))
        assert(jvmMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
      }
    }
    "jvm2123" - {
      if (Properties.isJavaAtLeast(17)) "Scala 2.12 tests don't support Java 17+"
      else {
        assert(eval("upickleJvm[2.12.3].test"))
        val jvmMeta = meta("upickleJvm[2.12.3].test.test")
        assert(jvmMeta.contains("example.ExampleTests.simple"))
        assert(jvmMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
      }
    }
  }
}
