package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.testkit.internal.SonatypeCentralTestUtils
import utest.*

object InitGpgPublishTests extends UtestIntegrationTestSuite {
  private def extractExportValue(output: String, name: String): String =
    (s"(?m)^export ${java.util.regex.Pattern.quote(name)}=(.*)$$").r
      .findFirstMatchIn(output)
      .map(_.group(1).trim)
      .getOrElse("")

  private def initGpgKeysSmokeTest(): Unit = integrationTest { tester =>
    import tester.*
    
    val res = eval(
      "mill.javalib.SonatypeCentralPublishModule/initGpgKeys"
      stdin = Seq(
        "Mill Test User\n",
        "mill-test-user@example.com\n",
        "mill-test-passphrase\n"
      ).mkString,
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
      "testProject.publishSonatypeCentral",
      "testProject/publishSonatypeCentral.dest",
      secretBase64,
      Some(passphrase)
    )
  }

  val tests: Tests = Tests {
    test("initGpgKeys") - initGpgKeysSmokeTest()
  }
}
