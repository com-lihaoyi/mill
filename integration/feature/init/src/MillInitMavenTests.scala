package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitMavenTests extends UtestIntegrationTestSuite {

  def downloadExtractTo(workspace: os.Path, zipUrl: String, zipContentDir: String): Unit = {
    val zipFile = os.temp(requests.get(zipUrl))
    val contentDir = os.unzip(zipFile, os.temp.dir()) / zipContentDir
    os.list(contentDir).foreach(os.move.into(_, workspace))
  }

  def tests = Tests {

    test("Mill init imports a Maven project") - integrationTest { tester =>
      import tester._

      downloadExtractTo(
        workspacePath,
        // - uses Junit5
        // - specifies --release javac option
        "https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip",
        "jansi-jansi-2.4.1"
      )

      val initRes = eval("init")
      initRes.isSuccess ==> true

      val resolveRes = eval(("resolve", "_"))
      Seq(
        "compile",
        "test",
        "publish"
      ).forall(resolveRes.out.contains) ==> true

      val compileRes = eval("compile")
      compileRes.isSuccess ==> true

      val testRes = eval("test")
      testRes.isSuccess ==> true
      testRes.out.contains(
        "Test run finished: 0 failed, 1 ignored, 90 total"
      ) ==> true

      val publishLocalRes = eval("publishLocal")
      publishLocalRes.isSuccess ==> true
      publishLocalRes.err.contains( // why is this in err?
        "Publishing Artifact(FuseSource, Corp.,jansi,2.4.1)") ==> true
    }

    test("Mill init imports a multi-module Maven project") - integrationTest { tester =>
      import tester._

      downloadExtractTo(
        workspacePath,
        // - uses Junit4
        "https://github.com/super-csv/super-csv/archive/refs/tags/v2.4.0.zip",
        "super-csv-2.4.0"
      )

      val initRes = eval("init")
      initRes.isSuccess ==> true

      val resolveRes = eval(("resolve", "_"))
      resolveRes.isSuccess ==> true
      Seq(
        "super-csv",
        "super-csv-benchmark",
        "super-csv-distribution",
        "super-csv-dozer",
        "super-csv-java8",
        "super-csv-joda"
      ).forall(resolveRes.out.contains) ==> true
    }
  }
}
