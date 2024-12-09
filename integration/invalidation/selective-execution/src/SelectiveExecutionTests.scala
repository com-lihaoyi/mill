package mill.integration
import mill.testkit.UtestIntegrationTestSuite

import utest._

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("selective-changed-inputs") - integrationTest { tester =>
      import tester._

      eval(("selectivePrepare", "{foo.fooCommand,bar.barCommand}"), check = true)
      modifyFile(workspacePath / "bar/bar.txt", _ + "!")
      val cached = eval(("selectiveRun", "{foo.fooCommand,bar.barCommand}"), check = true, stderr = os.Inherit)

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }
    test("selective-changed-code") - integrationTest { tester =>
      import tester._

      // Check method body code changes correctly trigger downstream evaluation
      eval(("selectivePrepare", "{foo.fooCommand,bar.barCommand}"), check = true, stderr = os.Inherit)
      modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))
      val cached1 = eval(("selectiveRun", "{foo.fooCommand,bar.barCommand}"), check = true, stderr = os.Inherit)

      assert(!cached1.out.contains("Computing fooCommand"))
      assert(cached1.out.contains("Computing barCommand"))

      // Check module body code changes correctly trigger downstream evaluation
      eval(("selectivePrepare", "{foo.fooCommand,bar.barCommand}"), check = true, stderr = os.Inherit)
      modifyFile(workspacePath / "build.mill", _.replace("object foo extends Module {", "object foo extends Module { println(123)"))
      val cached2 = eval(("selectiveRun", "{foo.fooCommand,bar.barCommand}"), check = true, stderr = os.Inherit)

      assert(cached2.out.contains("Computing fooCommand"))
      assert(!cached2.out.contains("Computing barCommand"))
    }
  }
}
