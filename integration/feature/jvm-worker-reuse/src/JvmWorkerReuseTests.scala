package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object JvmWorkerReuseTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    test("envVars") - integrationTest { tester =>
      def getProcessIds() = os
        .walk(tester.workspacePath / "out/mill.javalib.JvmWorkerModule/internalWorker.dest/zinc-worker")
        .filter(_.last == "server.log")
        .flatMap(os.read.lines(_))
        .collect {  case s"pid:$num $rest" => num }
        .distinct

      tester.eval("foo.compile", check = true)
      assert(getProcessIds().size == 1)
//      tester.eval("foo.run", check = true)
//      pprint.log(getProcessId())
      tester.eval("bar.compile", check = true)
      // bar has same jvmId as foo, ZincWorkerMain should be reused
      assert(getProcessIds().size == 1)
//      tester.eval("bar.run", check = true)
//      pprint.log(getProcessId())
      tester.eval("qux.compile", check = true)
      // qux has different jvmId as bar, ZincWorkerMain should be re-created
      assert(getProcessIds().size == 2)
//      pprint.log(getProcessId())
//      tester.eval("qux.run", check = true)
//      pprint.log(getProcessId())
      tester.eval("baz.compile", check = true)
      // baz has same jvmId as qux, ZincWorkerMain should be reused
      assert(getProcessIds().size == 2)
//      pprint.log(getProcessId())
//      tester.eval("baz.run", check = true)
//      pprint.log(getProcessId())
    }
  }
}
