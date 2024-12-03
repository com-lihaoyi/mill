package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object ReproducibilityTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - {
      def run() = integrationTest { tester =>
        tester.eval(("--meta-level", "1", "runClasspath"))
        tester.workspacePath
      }

      def normalize(workspacePath: os.Path) = {
        for(p <- os.walk(workspacePath / "out")){
          val sub = p.subRelativeTo(workspacePath).toString()

          val cacheable =
            (sub.contains(".dest") || sub.contains(".json") || os.isDir(p)) &&
            !sub.replace("out/mill-build", "").contains("mill-") &&
            !(p.ext == "json" && ujson.read(os.read(p)).objOpt.flatMap(_.get("value")).flatMap(_.objOpt).flatMap(_.get("worker")).nonEmpty)

          if (!cacheable) {
            os.remove.all(p)
          }
        }

      }
      val workspacePath1 = run()
      val workspacePath2 = run()
      assert(workspacePath1 != workspacePath2)
      normalize(workspacePath1)
      normalize(workspacePath2)
      val diff = os.call(("git", "diff", "--no-index", workspacePath1, workspacePath2)).out.text()
      pprint.log(diff)
      assert(diff.isEmpty)
    }
  }
}
