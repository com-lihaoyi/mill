package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitMavenTests extends UtestIntegrationTestSuite {

  private def downloadExtractTo(workspace: os.Path, zipUrl: String, zipContentDir: String): Unit = {
    val zipFile = os.temp(requests.get(zipUrl))
    val contentDir = os.unzip(zipFile, os.temp.dir()) / zipContentDir
    os.list(contentDir).foreach(os.move.into(_, workspace))
  }

  def tests: Tests = Tests {

    test("Mill init imports a Maven project") - integrationTest { tester =>
      import tester._

      downloadExtractTo(
        workspacePath,
        // - uses Junit5
        // - uses --release javac option
        "https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip",
        "jansi-jansi-2.4.1"
      )

      val initRes = eval("init")
      initRes.isSuccess ==> true

      val resolveRes = eval(("resolve", "_"))
      Seq(
        "compile",
        "test",
        "publishLocal"
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
        // - uses unsupported test framework
        // - uses hyphens in module names
        "https://github.com/avaje/avaje-config/archive/refs/tags/4.0.zip",
        "avaje-config-4.0"
      )

      val initRes = eval("init")
      initRes.isSuccess ==> true

      val resolveRes = eval(("resolve", "_"))
      resolveRes.isSuccess ==> true
      Seq(
        "avaje-config",
        "avaje-aws-appconfig",
        "avaje-dynamic-logback"
      ).forall(resolveRes.out.contains) ==> true

      val compileRes = eval("avaje-config.compile")
      // probably a JPMS issue but we only care about package task resolution
      compileRes.err.contains(
        "src/main/java/module-info.java:5:31: module not found: io.avaje.lang"
      ) ==> true
    }
  }
}
