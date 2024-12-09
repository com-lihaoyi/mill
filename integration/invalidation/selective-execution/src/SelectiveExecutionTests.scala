import mill.testkit.UtestIntegrationTestSuite

import utest._

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("control") - integrationTest { tester =>
      import tester._

      val initial = eval("{bar,baz}")
      assert(initial.out.contains("Computing bar"))
      assert(initial.out.contains("Computing baz"))

      modifyFile(workspacePath / "qux.txt", _ + "!")

      val cached = eval("{bar,baz}")
      assert(cached.out.contains("Computing bar"))
      assert(cached.out.contains("Computing baz"))
    }
    test("selective-changed-inputs") - integrationTest { tester =>
      import tester._

      val initial = eval("{bar,baz}")
      assert(initial.out.contains("Computing bar"))
      assert(initial.out.contains("Computing baz"))

      eval(("selectivePrepare", "{bar,baz}"), check = true)
      modifyFile(workspacePath / "qux.txt", _ + "!")
      val cached = eval(("selectiveRun", "{bar,baz}"), check = true, stderr = os.Inherit)

      assert(!cached.out.contains("Computing bar"))
      assert(cached.out.contains("Computing baz"))
    }
  }
}
