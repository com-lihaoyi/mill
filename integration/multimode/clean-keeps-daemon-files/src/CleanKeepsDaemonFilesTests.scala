package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * `clean __` must not delete Mill's own `out/mill-*` state. `__` resolves tasks on the
 * root module, whose `Segments` is empty, which used to make `clean` treat `out/` itself
 * as a module folder and wipe it wholesale, including the running process's `processId`
 * file. The process watchdog then exits mid-command, surfacing to the user as
 * "Worker wire broken, worker likely crashed".
 *
 * See https://github.com/com-lihaoyi/mill/issues/7053
 */
object CleanKeepsDaemonFilesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      import tester.*

      val res1 = eval(("show", "foo.bar"))
      assert(res1.isSuccess)
      assert(os.exists(workspacePath / "out/foo/bar.json"))

      val processRoot =
        if (tester.daemonMode) workspacePath / "out/mill-daemon"
        else workspacePath / "out/mill-no-daemon"
      assert(os.exists(processRoot))

      val res2 = eval(("clean", "__"))

      // The command itself must succeed rather than dying with a broken connection
      assert(res2.isSuccess)
      assert(!res2.err.contains("Worker wire broken"))
      assert(!res2.err.contains("processId file missing"))

      // Task output is cleaned...
      assert(!os.exists(workspacePath / "out/foo/bar.json"))
      // ...but Mill's own bookkeeping survives
      assert(os.exists(processRoot))
      assert(os.exists(workspacePath / "out"))

      // And Mill still works afterwards
      val res3 = eval(("show", "foo.bar"))
      assert(res3.isSuccess)
    }
  }
}
