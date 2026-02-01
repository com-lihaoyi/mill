package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.testkit.internal.SonatypeCentralTestUtils
import utest.*

object InitGpgPublishTests extends UtestIntegrationTestSuite {
  private val PublishTaskName = "testProject.publishSonatypeCentral"
  private val PublishDirName = os.SubPath("testProject/publishSonatypeCentral.dest")
  private val InitGpgKeysTaskName = "mill.javalib.SonatypeCentralPublishModule/initGpgKeys"

  private def extractExportValue(output: String, name: String): String =
    (s"(?m)^export ${java.util.regex.Pattern.quote(name)}=(.*)$$").r
      .findFirstMatchIn(output)
      .map(_.group(1).trim)
      .getOrElse("")

  private def initGpgKeysSmokeTest(): Unit = integrationTest { tester =>
    import tester.*

    val stdin =
      Seq("Mill Test User", "mill-test-user@example.com", "mill-test-passphrase").mkString("\n") +
        "\n"
    val res = eval(
      Seq("--no-daemon", InitGpgKeysTaskName),
      stdin = stdin,
      mergeErrIntoOut = true
    )
    println(res.debugString)
    assert(res.isSuccess)

    val output = res.out
    assert(output.contains("PGP Key Setup for Sonatype Central Publishing"))
    assert(output.contains("PGP key generated successfully"))
    assert(output.contains("Key verified on keyserver!"))
    val secretBase64 = extractExportValue(output, "MILL_PGP_SECRET_BASE64")
    val passphrase = extractExportValue(output, "MILL_PGP_PASSPHRASE")
    assert(secretBase64.nonEmpty)
    assert(passphrase == "mill-test-passphrase")

    SonatypeCentralTestUtils.dryRunWithKey(
      tester,
      PublishTaskName,
      PublishDirName,
      secretBase64,
      Some(passphrase)
    )
  }

  val tests: Tests = Tests {
    test("initGpgKeys") - initGpgKeysSmokeTest()
  }
}
