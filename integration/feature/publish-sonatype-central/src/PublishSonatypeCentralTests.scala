import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.UtestIntegrationTestSuite
import utest.*

object PublishSonatypeCentralTests extends UtestIntegrationTestSuite {
  private val ENV_VAR_DRY_RUN = "MILL_TESTS_PUBLISH_DRY_RUN"
  private val PublishTaskName = "testProject.publishSonatypeCentral"
  private val PublishDirName = os.SubPath("testProject/publishSonatypeCentral.dest")
  private val PublishAllTaskName = "mill.javalib.SonatypeCentralPublishModule/publishAll"
  private val PublishAllDirName =
    os.SubPath("mill.javalib.SonatypeCentralPublishModule/publishAll.dest")
  private val InitGpgKeysTaskName = "mill.javalib.SonatypeCentralPublishModule/initGpgKeys"
  private val TestPgpSecretBase64 =
    "LS0tLS1CRUdJTiBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQoKeFZnRWFHekhpeFlKS3dZQkJBSGFSdzhCQVFkQWxQamhsaGo5MUtZUnhDQXFtaUZNMjR1UEVDL0kxemR0CnlWS2dRR1lENHZZQUFQOW9jK0ZFQzQ2dkt6b0tNWVE3M1Jvemh4UDE3WWhUZnZwRFBwYk1CZHNZQ2c2RQp6VEpwYnk1bmFYUm9kV0l1WVhKMGRYSmhlaTUwWlhOMFVISnZhbVZqZENCaWIzUWdQR0Z6UUdGeWRIVnkKWVhvdWJtVjBQc0tNQkJBV0NnQWRCUUpvYk1lTEJBc0pCd2dERlFnS0JCWUFBZ0VDR1FFQ0d3TUNIZ0VBCklRa1FBMkRDK3lxemF1RVdJUVRnUmJWQ05LcVpxRTFkdDB3RFlNTDdLck5xNFR1L0FQNHRDYzZpYWNUdQpZVEJBa2Q3UDZOM1E1VTZjbGdnSElVQ2lRL3lIbmFvVHZ3RUExbU92M2MydEVORGtrdnF5Ujl2YVhWNHEKZlBEckNDRmRTUTR0anpMY3hnVEhYUVJvYk1lTEVnb3JCZ0VFQVpkVkFRVUJBUWRBUHpzMjV5RERLSC80Cm1KNmtMU1dLSExITXJEWUZMWGVHOTNWRTluSVY0Q0FEQVFnSEFBRC9aQ1hVMDhqMkZTU2VYQWdZaFZzNwp2akVDQjQweTA2TjdaM0pqaitCSko3Z08xc0o0QkJnV0NBQUpCUUpvYk1lTEFoc01BQ0VKRUFOZ3d2c3EKczJyaEZpRUU0RVcxUWpTcW1haE5YYmRNQTJEQyt5cXphdUgrY2dEL1QxRUVkVDl1WnR6L255bGk1OHR0CjYxaWNLcndyU3kzSTBBRDNYWWErcm40QS9qWEZlZXNsNVBZZWtpU0ZzNVZGNUczRVNpWmY0amJxZXlOWQpLd09ENVIwSwo9WDhSdQotLS0tLUVORCBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQo="

  private def dryRun(taskName: String, dirName: os.SubPath): Unit = integrationTest { tester =>
    import tester.*

    val res = eval(
      taskName,
      env = Map(
        USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
        PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password",
        ENV_VAR_DRY_RUN -> "1",
        "MILL_PGP_SECRET_BASE64" -> TestPgpSecretBase64
      )
    )
    println(res.debugString)
    // Extract the values so that `assert` macro would print them out nicely if the test fails
    // instead of printing `res` twice.
    val isSuccess = res.isSuccess
    assert(isSuccess)

    val dir =
      workspacePath / "out" / dirName / "repository" / "io.github.lihaoyi.testProject_3-0.0.1"

    val expectedFiles = Vector(
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar.asc",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar.asc.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar.asc.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar.asc",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar.asc.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar.asc.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar.asc",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar.asc.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar.asc.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom.asc",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom.asc.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom.asc.sha1",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom.md5",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom.sha1"
    )
    val actualFiles = os.walk(dir).toVector
    val missingFiles = expectedFiles.filterNot(actualFiles.contains)
    assert(missingFiles.isEmpty)

    val signTargets = Vector(
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-javadoc.jar",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1-sources.jar",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.jar",
      dir / "io/github/lihaoyi/testProject_3/0.0.1/testProject_3-0.0.1.pom"
    )
    withGpgHome(TestPgpSecretBase64) { gpgEnv =>
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

  private def gpgDigest(file: os.Path, algorithm: String, gpgEnv: Map[String, String]): String = {
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

  private def initGpgKeysSmokeTest(): Unit = integrationTest { tester =>
    import tester.*

    val stdin =
      Seq("Mill Test User", "mill-test-user@example.com", "mill-test-passphrase").mkString("\n") +
        "\n"
    val res = eval(
      InitGpgKeysTaskName,
      stdin = stdin,
      mergeErrIntoOut = true
    )
    println(res.debugString)
    assert(res.isSuccess)

    val out = res.out
    assert(out.contains("PGP Key Setup for Sonatype Central Publishing"))
    assert(out.contains("PGP key generated successfully"))
    assert(out.contains("MILL_PGP_SECRET_BASE64"))
    assert(out.contains("MILL_PGP_PASSPHRASE"))
  }

  val tests: Tests = Tests {
    test("dryRun") {
      test("module") - dryRun(PublishTaskName, PublishDirName)
      test("externalModule") - dryRun(PublishAllTaskName, PublishAllDirName)
    }
    test("initGpgKeys") - initGpgKeysSmokeTest()
  }
}
