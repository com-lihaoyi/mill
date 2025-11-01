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
      val res1 = eval(("Foo.scala:runMain", "main1", "--text" , "hello"))
      assert(res1.out == "hello123")

      val res2 = eval(("Foo.scala:runMain", "main2", "--text" , "world"))
      assert(res2.out == "world456")

      val res3 = eval(("Foo.scala:runMain", "main3", "--text" , "moooo"))
      assert(res3.out == "moooo789")
    }
  }
}
