import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

object InitGpgPublishTests extends UtestIntegrationTestSuite {
  private val ENV_VAR_DRY_RUN = "MILL_TESTS_PUBLISH_DRY_RUN"
  private val PublishTaskName = "testProject.publishSonatypeCentral"
  private val PublishDirName = os.SubPath("testProject/publishSonatypeCentral.dest")
  private val InitGpgKeysTaskName = "mill.javalib.SonatypeCentralPublishModule/initGpgKeys"

  private def dryRunWithKey(
      tester: IntegrationTester,
      taskName: String,
      dirName: os.SubPath,
      secretBase64: String,
      passphrase: Option[String]
  ): Unit = {
    import tester.*

    val env = Map(
      USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
      PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password",
      ENV_VAR_DRY_RUN -> "1",
      "MILL_PGP_SECRET_BASE64" -> secretBase64
    ) ++ passphrase.map("MILL_PGP_PASSPHRASE" -> _)
    val res = eval(taskName, env = env)
    println(res.debugString)
    assert(res.isSuccess)

    val dir =
      workspacePath / "out" / dirName / "repository" / "io.github.lihaoyi.testProject-0.0.1"
    val signTargets = Vector(
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar",
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar",
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar",
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom"
    )
    withGpgHome(secretBase64) { gpgEnv =>
      signTargets.foreach { file =>
        assertSignatureValid(file, gpgEnv)
        assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5", gpgEnv)
        assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1", gpgEnv)
      }
    }
  }

  private def withGpgHome[T](secretKeyBase64: String)(f: Map[String, String] => T): T = {
    val gpgHome = os.temp.dir(prefix = "mill-gpg")
    val gpgEnv = Map("GNUPGHOME" -> gpgHome.toString)
    val secretBytes = java.util.Base64.getDecoder.decode(secretKeyBase64)
    os.proc("gpg", "--batch", "--yes", "--import").call(stdin = secretBytes, env = gpgEnv)
    f(gpgEnv)
  }

  private def assertSignatureValid(file: os.Path, gpgEnv: Map[String, String]): Unit = {
    val signature = os.Path(file.toString + ".asc")
    os.proc("gpg", "--batch", "--verify", signature.toString, file.toString)
      .call(env = gpgEnv)
  }

  private def assertChecksumMatches(
      file: os.Path,
      checksumFile: os.Path,
      algorithm: String,
      gpgEnv: Map[String, String]
  ): Unit = {
    val expected = os.read(checksumFile).trim.split("\\s+").headOption.getOrElse("")
    val actual = gpgDigest(file, algorithm, gpgEnv)
    assert(expected.nonEmpty && expected.equalsIgnoreCase(actual))
  }

  private def gpgDigest(
      file: os.Path,
      algorithm: String,
      gpgEnv: Map[String, String]
  ): String = {
    val res = os.proc("gpg", "--print-md", algorithm, file.toString)
      .call(env = gpgEnv, stderr = os.Pipe)
    val out = res.out.text() + res.err.text()
    val expectedLen = algorithm.toUpperCase match {
      case "MD5" => 32
      case "SHA1" => 40
      case _ => 0
    }
    extractHexDigest(out, expectedLen)
  }

  private def extractHexDigest(output: String, expectedLen: Int): String = {
    if (expectedLen <= 0) return ""
    val exact = s"(?i)([0-9a-f]{$expectedLen})".r
    exact.findFirstMatchIn(output).map(_.group(1)).getOrElse {
      val loose = "(?i)([0-9a-f][0-9a-f ]{15,})".r
      loose.findFirstMatchIn(output).flatMap { m =>
        val cleaned = m.group(1).replace(" ", "")
        if (cleaned.length >= expectedLen) Some(cleaned.take(expectedLen)) else None
      }.getOrElse("")
    }
  }

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

    dryRunWithKey(tester, PublishTaskName, PublishDirName, secretBase64, Some(passphrase))
  }

  val tests: Tests = Tests {
    test("initGpgKeys") - initGpgKeysSmokeTest()
  }
}
