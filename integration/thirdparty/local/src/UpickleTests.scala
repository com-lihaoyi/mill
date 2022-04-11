package mill.integration.thirdparty

import utest._

class UpickleTests(fork: Boolean)
    extends IntegrationTestSuite("MILL_UPICKLE_REPO", "upickle", fork) {
  val tests = Tests {
    initWorkspace()
    "jvm21111" - {
      mill.util.TestUtil.disableInJava9OrAbove({
        assert(eval("upickleJvm[2.11.11].test"))
        val jvmMeta = meta("upickleJvm[2.11.11].test.test")
        assert(jvmMeta.contains("example.ExampleTests.simple"))
        assert(jvmMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
      })
    }
    "jvm2123" - {
      assert(eval("upickleJvm[2.12.3].test"))
      val jvmMeta = meta("upickleJvm[2.12.3].test.test")
      assert(jvmMeta.contains("example.ExampleTests.simple"))
      assert(jvmMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
    }
    "js" - {
      assert(eval("upickleJs[2.12.3].test"))
      val jsMeta = meta("upickleJs[2.12.3].test.test")
      assert(jsMeta.contains("example.ExampleTests.simple"))
      assert(jsMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
    }

  }
}
