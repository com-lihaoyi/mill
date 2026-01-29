package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object ShadingTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("shading") - integrationTest { tester =>
      import tester._

      val run = eval("hello.run")
      assert(run.isSuccess)
      assert(run.out.contains("Gson class: shaded.gson.Gson"))
      assert(run.out.contains("IOUtils class: org.apache.commons.io.IOUtils"))

      val jar = eval("hello.jar")
      assert(jar.isSuccess)
      // Further jar verification could be added here if needed (e.g. unzipping and checking entries)
    }
  }
}
