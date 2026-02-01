package mill.testkit.internal

import mill.api.{Evaluator, Task}
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.{IntegrationTester, TestRootModule, UnitTester}

private[mill] object SonatypeCentralTestUtils {
  val DryRunEnvVar = "MILL_TESTS_PUBLISH_DRY_RUN"

  def dryRunWithKey(
      tester: IntegrationTester,
      taskName: String,
      dirName: os.SubPath,
      secretBase64: String,
      passphrase: Option[String]
  ): Unit = {
    import tester.*

    val env = baseDryRunEnv(secretBase64, passphrase)
    val res = eval(taskName, env = env)
    println(res.debugString)
    assert(res.isSuccess)

    val dir =
      workspacePath / "out" / dirName / "repository" / "io.github.lihaoyi.testProject-0.0.1"
    withGpgHome(secretBase64) { gpgEnv =>
      signedArtifacts(dir).foreach { file =>
        assertSignatureValid(file, gpgEnv)
        assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5", gpgEnv)
        assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1", gpgEnv)
      }
    }
  }

  def dryRunWithKey(
      task: Task[Unit],
      dirName: os.SubPath,
      secretBase64: String,
      passphrase: Option[String],
      module: TestRootModule,
      resourcePath: os.Path
  ): Unit = {
    val env = baseDryRunEnv(secretBase64, passphrase)
    UnitTester(
      module,
      resourcePath,
      env = Evaluator.defaultEnv ++ env
    ).scoped { eval =>
      val Right(_) = eval.apply(task).runtimeChecked
      val workspacePath = module.moduleDir

      val dir =
        workspacePath / "out" / dirName / "repository" / "io.github.lihaoyi.testProject-0.0.1"

      val expectedFiles = Vector(
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.sha1"
      )
      val actualFiles = os.walk(dir).toVector
      val missingFiles = expectedFiles.filterNot(actualFiles.contains)
      assert(missingFiles.isEmpty)

      withGpgHome(secretBase64) { gpgEnv =>
        signedArtifacts(dir).foreach { file =>
          assertSignatureValid(file, gpgEnv)
          assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5", gpgEnv)
          assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1", gpgEnv)
        }
      }
    }
  }

  def withGpgHome[T](secretKeyBase64: String)(f: Map[String, String] => T): T = {
    val gpgHome = os.temp.dir(prefix = "mill-gpg")
    val gpgEnv = Map("GNUPGHOME" -> gpgHome.toString)
    val secretBytes = java.util.Base64.getDecoder.decode(secretKeyBase64)
    os.proc("gpg", "--batch", "--yes", "--import").call(stdin = secretBytes, env = gpgEnv)
    f(gpgEnv)
  }

  def assertSignatureValid(file: os.Path, gpgEnv: Map[String, String]): Unit = {
    val signature = os.Path(file.toString + ".asc")
    os.proc("gpg", "--batch", "--verify", signature.toString, file.toString)
      .call(env = gpgEnv)
  }

  def assertChecksumMatches(
      file: os.Path,
      checksumFile: os.Path,
      algorithm: String,
      gpgEnv: Map[String, String] = Map.empty
  ): Unit = {
    val expected = os.read(checksumFile).trim.split("\\s+").headOption.getOrElse("")
    val actual = gpgDigest(file, algorithm, gpgEnv)
    assert(expected.nonEmpty && expected.equalsIgnoreCase(actual))
  }

  def gpgDigest(
      file: os.Path,
      algorithm: String,
      gpgEnv: Map[String, String] = Map.empty
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

  def extractHexDigest(output: String, expectedLen: Int): String = {
    if (expectedLen <= 0) return ""
    val hexLine = "(?m)^([0-9a-fA-F]{2,4}(?:\\s+[0-9a-fA-F]{2,4})+)$".r
    val fromLine = hexLine.findFirstMatchIn(output).flatMap { m =>
      val cleaned = m.group(1).replaceAll("\\s+", "")
      if (cleaned.length >= expectedLen) Some(cleaned.take(expectedLen)) else None
    }
    fromLine.getOrElse {
      val exact = s"(?i)([0-9a-f]{$expectedLen})".r
      exact.findFirstMatchIn(output).map(_.group(1)).getOrElse {
        val loose = "(?i)([0-9a-f][0-9a-f ]{15,})".r
        loose.findFirstMatchIn(output).flatMap { m =>
          val cleaned = m.group(1).replace(" ", "")
          if (cleaned.length >= expectedLen) Some(cleaned.take(expectedLen)) else None
        }.getOrElse("")
      }
    }
  }

  private def baseDryRunEnv(
      secretBase64: String,
      passphrase: Option[String]
  ): Map[String, String] =
    Map(
      USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
      PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password",
      DryRunEnvVar -> "1",
      "MILL_PGP_SECRET_BASE64" -> secretBase64
    ) ++ passphrase.map("MILL_PGP_PASSPHRASE" -> _)

  private def signedArtifacts(dir: os.Path): Vector[os.Path] =
    Vector(
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar",
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar",
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar",
      dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom"
    )
}
