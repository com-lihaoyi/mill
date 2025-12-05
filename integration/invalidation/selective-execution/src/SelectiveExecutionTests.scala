package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration._

import utest._
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("default-command") - integrationTest { tester =>
      import tester._

      eval(("selective.prepare", "bar"), check = true)

      val resolve = eval(("selective.resolve", "bar"), check = true)
      assert(resolve.out == "")

      modifyFile(workspacePath / "bar/bar.txt", _ + "!")
      val resolve2 = eval(("selective.resolve", "bar"), check = true)
      assert(resolve2.out != "")

      val cached = eval(("selective.run", "bar"), check = true, stderr = os.Inherit)

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }

    test("failures") {
      test("missing-prepare") - integrationTest { tester =>
        import tester._

        val cached = eval(
          ("selective.run", "{foo.fooCommand,bar.barCommand}"),
          check = false,
          stderr = os.Pipe
        )

        assert(cached.err.contains("`selective.run` can only be run after `selective.prepare`"))
      }
    }
    test("renamed-tasks") - integrationTest { tester =>
      import tester._
      eval(("selective.prepare", "{foo,bar}._"), check = true)

      modifyFile(workspacePath / "build.mill", _.replace("fooTask", "fooTaskRenamed"))
      modifyFile(workspacePath / "build.mill", _.replace("barCommand", "barCommandRenamed"))

      val cached = eval(("selective.resolve", "{foo,bar}._"), stderr = os.Pipe)

      assert(
        cached.out.linesIterator.toList.sorted ==
          Seq("bar.barCommandRenamed", "foo.fooCommand", "foo.fooTaskRenamed")
      )
    }
    test("overrideSuper") - integrationTest { tester =>
      import tester._
      eval(("selective.prepare", "qux.quxCommand"), check = true)

      modifyFile(
        workspacePath / "build.mill",
        _.replace("Computing quxCommand", "Computing quxCommand!")
      )

      val cached = eval(("selective.run", "qux.quxCommand"), stderr = os.Pipe)

      assert(
        cached.out.linesIterator.toList ==
          Seq("Computing quxCommand!")
      )
    }
  }
}
