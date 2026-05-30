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

  // The binary Zinc analysis file is gzip-compressed; decompress so we scan its actual content.
  def gunzipIfNeeded(bytes: Array[Byte]): Array[Byte] =
    if (bytes.length >= 2 && bytes(0) == 0x1f.toByte && bytes(1) == 0x8b.toByte) {
      val in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))
      try in.readAllBytes()
      finally in.close()
    } else bytes

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
        tester.eval(("--meta-level", "1", "runClasspath"), check = true)
        tester.eval("javaApp.run", check = true)
        tester.eval("javaApp.assembly", check = true)
        tester.eval("scalaApp.run", check = true)
        tester.eval("scalaApp.assembly", check = true)
        tester.eval("kotlinApp.run", check = true)
        tester.eval("kotlinApp.assembly", check = true)
        tester.workspacePath
      }

      val workspacePath = run()
      normalize(workspacePath)

      val ws = BuildCtx.workspaceRoot.toString.getBytes("UTF-8")
      val hm = os.home.toString.getBytes("UTF-8")
      val leaks = collection.mutable.Buffer.empty[String]
      for (p <- os.walk(workspacePath / "out", followLinks = false) if os.isFile(p)) {
        val bytes = gunzipIfNeeded(os.read.bytes(p))
        val sub = p.subRelativeTo(workspacePath)
        if (bytes.containsSlice(ws)) leaks.append(s"[WS] $sub")
        else if (bytes.containsSlice(hm)) leaks.append(s"[HM] $sub")
      }
      Predef.assert(leaks.isEmpty, leaks.mkString("\n"))
    }
  }
}
