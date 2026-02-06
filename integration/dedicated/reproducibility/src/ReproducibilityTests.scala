package mill.integration

import mill.api.BuildCtx
import mill.testkit.UtestIntegrationTestSuite
import utest.*

import java.util.zip.GZIPInputStream

object ReproducibilityTests extends UtestIntegrationTestSuite {

  def normalize(workspacePath: os.Path): Unit = {
    for (p <- os.walk(workspacePath / "out")) {
      val sub = p.subRelativeTo(workspacePath).toString()

      val cacheable =
        (sub.contains(".dest") || sub.contains(".json") || os.isDir(p)) &&
          !sub.replace("out/mill-build", "").contains("mill-") &&
          !(p.ext == "json" && ujson.read(
            os.read(p)
          ).objOpt.flatMap(_.get("value")).flatMap(_.objOpt).flatMap(_.get("worker")).nonEmpty)

      if (!cacheable) {
        os.remove.all(p)
      }
    }
  }

  val tests: Tests = Tests {
    test("diff") - {
      def run() = integrationTest { tester =>
        val res = tester.eval(("show", "foo"))
        val lastNonEmptyLine =
          res.out.linesIterator.filter(_.nonEmpty).toSeq.lastOption.getOrElse("")
        assert(lastNonEmptyLine == "31337")
        tester.workspacePath
      }

      val workspacePath1 = run()
      val workspacePath2 = run()
      assert(workspacePath1 != workspacePath2)
      normalize(workspacePath1)
      normalize(workspacePath2)
      val diff = os.call(("git", "diff", "--no-index", workspacePath1, workspacePath2)).out.text()
      assert(diff.isEmpty)
    }

    test("inspection") - {
      def run() = integrationTest { tester =>
        tester.eval(
          ("--meta-level", "1", "runClasspath"),
          env = Map("MILL_TEST_TEXT_ANALYSIS_STORE" -> "1"),
          check = true
        )
        tester.workspacePath
      }

      val workspacePath = run()
      val dest = workspacePath / "out/mill-build/compile.dest/zinc.txt"
      val src = workspacePath / "out/mill-build/compile.dest/zinc"
      os.write(dest, new GZIPInputStream(os.read.inputStream(src)))
      normalize(workspacePath)
      for (p <- os.walk(workspacePath)) {
        if (
          (p.ext == "json" || p.ext == "txt")
          && !p.segments.contains("enablePluginScalacOptions.super")
          && !p.segments.contains("allScalacOptions.json")
          && !p.segments.contains("scalacOptions.json")
          && p.last != "coursierEnv.json"
        ) {
          val txt = os.read(p)
          Predef.assert(!txt.contains(BuildCtx.workspaceRoot.toString), p)
          Predef.assert(!txt.contains(os.home.toString), p)
        }
      }
    }
  }
}
