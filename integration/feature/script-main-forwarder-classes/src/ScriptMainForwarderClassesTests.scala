package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

// Make sure that for scripts with multiple `@main` methods (aliased to `@mainargs.main`),
// we generate synthetic main classes for each one following the name of the class that we
// can run using `runMain`. This mimics the behavior of `@scala.main`, and allows interop
// with tools that expect that behavior such as the IntelliJ `run` button
object ScriptMainForwarderClassesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      // When using `run`, we ignore the synthetic main classes when picking a main method,
      // so in this case we run the default `_MillScriptMain` method which delegates
      // to the relevant method internally based on the first token
      val res0 = eval(("Multi.scala:run", "main1", "--text", "HELLO"))
      assert(res0.out == "HELLO123")

      // Multi-main script have forwarder classes synthesized for each @mainargs.main method,
      // which forward to `_MillScriptMain` but pass the name as the first param to disambiguate
      val res1 = eval(("Multi.scala:runMain", "main1", "--text", "hello"))
      assert(res1.out == "hello123")

      val res2 = eval(("Multi.scala:runMain", "main2", "--text", "world"))
      assert(res2.out == "world456")

      val res3 = eval(("Multi.scala:runMain", "main3", "--text", "moooo"))
      assert(res3.out == "moooo789")

      // Single-main script, forwarder class must *not* pass the method name as
      // the first parameter
      val res4 = eval(("Single.scala:runMain", "main1", "--text", "iamcow"))
      assert(res4.out == "iamcowXYZ")

      // scala.main method takes priority over synthetic _MillScriptMain method
      val res5 = eval(("ScalaMain.scala:run", "hearmemoo"))
      assert(res5.out == "hearmemooABC")

      // `def main(args: Array[String]): Unit` method takes priority over synthetic _MillScriptMain method
      val res6 = eval(("RawMainSignature.scala:run", "iweightwiceasmuchasyou"))
      assert(res6.out == "iweightwiceasmuchasyouOMG")
    }
  }
}
