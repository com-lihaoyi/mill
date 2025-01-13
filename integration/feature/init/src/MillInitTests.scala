package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("Mill init works") - integrationTest { tester =>
      import tester._
      val msg =
        """Run `mill init <example-id>` with one of these examples as an argument to download and extract example.
          |Run `mill init --show-all` to see full list of examples.
          |Run `mill init <Giter8 template>` to generate project from Giter8 template.""".stripMargin
      val res = eval("init")
      res.isSuccess ==> true

      val exampleListOut = out("init")
      val parsed = exampleListOut.json.arr.map(_.str)
      assert(parsed.nonEmpty)
      assert(res.out.startsWith(msg))
      assert(res.out.endsWith(msg))
    }

    test("Mill init works for g8 templates") - integrationTest { tester =>
      import tester._
      eval(("init", "com-lihaoyi/mill-scala-hello.g8", "--name=example")).isSuccess ==> true
      val projFile = workspacePath / "example/build.sc"
      assert(os.exists(projFile))
    }
  }
}
