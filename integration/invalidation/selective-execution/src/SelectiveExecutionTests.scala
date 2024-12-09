import mill.testkit.UtestIntegrationTestSuite

import utest._

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("control") - integrationTest { tester =>
      import tester._

      val initial = eval("{fooCommand,barCommand}")
      assert(initial.out.contains("Computing fooCommand"))
      assert(initial.out.contains("Computing barCommand"))

      modifyFile(workspacePath / "bar.txt", _ + "!")

      val cached = eval("{fooCommand,barCommand}")
      assert(cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }
    test("selective-changed-inputs") - integrationTest { tester =>
      import tester._

      val initial = eval("{fooCommand,barCommand}")
      assert(initial.out.contains("Computing fooCommand"))
      assert(initial.out.contains("Computing barCommand"))

      eval(("selectivePrepare", "{fooCommand,barCommand}"), check = true)
      modifyFile(workspacePath / "bar.txt", _ + "!")
      val cached = eval(("selectiveRun", "{fooCommand,barCommand}"), check = true, stderr = os.Inherit)

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }
  }
}
