package mill.testkit.internal

import mill.api.{Evaluator, Task}
import mill.constants.EnvVars
import mill.testkit.{IntegrationTester, TestRootModule, UnitTester}

private[mill] object SonatypeCentralTestUtils {

  def dryRunWithKey(
      tester: IntegrationTester,
      taskName: String,
      dirName: os.SubPath,
      secretBase64: String,
      passphrase: Option[String]
  ): Unit = {
    import tester.*

    val env = baseDryRunEnv(Some(secretBase64), passphrase)
    val res = eval(taskName, env = env)
    println(res.debugString)
    assert(res.isSuccess)

    val dir = releaseRepoDir(
      workspacePath / "out" / dirName / "repository",
      group = "io.github.lihaoyi",
      artifactId = "testProject",
      version = "0.0.1"
    )
    withGpgHome(secretBase64) { gpgEnv =>
      signedArtifacts(dir, artifactId = "testProject", version = "0.0.1").foreach { file =>
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
      resourcePath: os.Path,
      group: String,
      artifactId: String,
      version: String
  ): Unit = {
    dryRunWithKey(
      task,
      dirName,
      Some(secretBase64),
      passphrase,
      module,
      resourcePath
    ) { repoDir =>
      val dir = releaseRepoDir(repoDir, group, artifactId, version)
      val baseDir = dir / releaseGroupPath(group) / artifactId / version
      val expectedFiles = Vector(
        baseDir / s"$artifactId-$version-javadoc.jar",
        baseDir / s"$artifactId-$version-javadoc.jar.asc",
        baseDir / s"$artifactId-$version-javadoc.jar.asc.md5",
        baseDir / s"$artifactId-$version-javadoc.jar.asc.sha1",
        baseDir / s"$artifactId-$version-javadoc.jar.md5",
        baseDir / s"$artifactId-$version-javadoc.jar.sha1",
        baseDir / s"$artifactId-$version-sources.jar",
        baseDir / s"$artifactId-$version-sources.jar.asc",
        baseDir / s"$artifactId-$version-sources.jar.asc.md5",
        baseDir / s"$artifactId-$version-sources.jar.asc.sha1",
        baseDir / s"$artifactId-$version-sources.jar.md5",
        baseDir / s"$artifactId-$version-sources.jar.sha1",
        baseDir / s"$artifactId-$version.jar",
        baseDir / s"$artifactId-$version.jar.asc",
        baseDir / s"$artifactId-$version.jar.asc.md5",
        baseDir / s"$artifactId-$version.jar.asc.sha1",
        baseDir / s"$artifactId-$version.jar.md5",
        baseDir / s"$artifactId-$version.jar.sha1",
        baseDir / s"$artifactId-$version.pom",
        baseDir / s"$artifactId-$version.pom.asc",
        baseDir / s"$artifactId-$version.pom.asc.md5",
        baseDir / s"$artifactId-$version.pom.asc.sha1",
        baseDir / s"$artifactId-$version.pom.md5",
        baseDir / s"$artifactId-$version.pom.sha1"
      )
      val actualFiles = os.walk(dir).toVector
      val missingFiles = expectedFiles.filterNot(actualFiles.contains)
      assert(missingFiles.isEmpty)

      withGpgHome(secretBase64) { gpgEnv =>
        signedArtifacts(baseDir, artifactId, version).foreach { file =>
          assertSignatureValid(file, gpgEnv)
          assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5", gpgEnv)
          assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1", gpgEnv)
        }
      }
    }
  }

  def dryRunWithKey(
      task: Task[Unit],
      dirName: os.SubPath,
      secretBase64: Option[String],
      passphrase: Option[String],
      module: TestRootModule,
      resourcePath: os.Path
  )(validateRepo: os.Path => Unit): Unit = {
    val env = baseDryRunEnv(secretBase64, passphrase)
    UnitTester(
      module,
      resourcePath,
      env = Evaluator.defaultEnv ++ env
    ).scoped { eval =>
      val Right(_) = eval.apply(task).runtimeChecked
      val workspacePath = module.moduleDir
      val repoDir = workspacePath / "out" / dirName / "repository"
      validateRepo(repoDir)
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
      secretBase64: Option[String],
      passphrase: Option[String]
  ): Map[String, String] =
    Map(
      EnvVars.MILL_SONATYPE_USERNAME -> "mill-tests-username",
      EnvVars.MILL_SONATYPE_PASSWORD -> "mill-tests-password",
      "MILL_TESTS_PUBLISH_DRY_RUN" -> "1"
    ) ++ secretBase64.map(EnvVars.MILL_PGP_SECRET_BASE64 -> _) ++
      passphrase.map(EnvVars.MILL_PGP_PASSPHRASE -> _)

  private def signedArtifacts(dir: os.Path, artifactId: String, version: String): Vector[os.Path] =
    Vector(
      dir / s"$artifactId-$version-javadoc.jar",
      dir / s"$artifactId-$version-sources.jar",
      dir / s"$artifactId-$version.jar",
      dir / s"$artifactId-$version.pom"
    )

  private def releaseRepoDir(
      repoDir: os.Path,
      group: String,
      artifactId: String,
      version: String
  ): os.Path =
    repoDir / s"$group.$artifactId-$version"

  private def releaseGroupPath(group: String): os.SubPath =
    os.SubPath(group.split('.').toIndexedSeq)
}
