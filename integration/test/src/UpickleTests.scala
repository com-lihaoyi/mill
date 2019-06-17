package mill.integration

import utest._

class UpickleTests(fork: Boolean) extends IntegrationTestSuite("MILL_UPICKLE_REPO", "upickle", fork) {
  val tests = Tests{
    initWorkspace()
    test("jvm21111"){
      mill.util.TestUtil.disableInJava9OrAbove({
        assert(eval("upickleJvm[2.11.11].test"))
        val jvmMeta = meta("upickleJvm[2.11.11].test.test")
        assert(jvmMeta.contains("example.ExampleTests.simple"))
        assert(jvmMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
      })
    }
    test("jvm2124"){
      assert(eval("upickleJvm[2.12.4].test"))
      val jvmMeta = meta("upickleJvm[2.12.4].test.test")
      assert(jvmMeta.contains("example.ExampleTests.simple"))
      assert(jvmMeta.contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
    }
    test("js"){
      assert(eval("upickleJs[2.12.4].test"))
      val jsMeta = meta("upickleJs[2.12.4].test.test")
      assert(jsMeta .contains("example.ExampleTests.simple"))
      assert(jsMeta .contains("upickle.MacroTests.commonCustomStructures.simpleAdt"))
    }

  }
}
