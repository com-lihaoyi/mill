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
      val expectedFiles = releaseExpectedFiles(baseDir, s"$artifactId-$version")
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

  def assertSnapshotRepository(
      repoDir: os.Path,
      group: String,
      artifactId: String,
      version: String
  ): Unit = {
    val publishedDir = repoDir / releaseGroupPath(group) / artifactId
    val rootMetadataFile = publishedDir / "maven-metadata.xml"
    assert(os.exists(rootMetadataFile))

    val rootMetadataContents = os.read(rootMetadataFile)
    assert(rootMetadataContents.contains(s"<version>$version</version>"))

    val publishedVersionDir = publishedDir / version
    val metadataFile = publishedVersionDir / "maven-metadata.xml"
    assert(os.exists(metadataFile))

    val metadataContents: String = os.read(metadataFile)
    assert(metadataContents.contains(s"<version>$version</version>"))

    val timestamp = extractSnapshotMetadataValue(
      metadataContents,
      """<timestamp>(\d{8}\.\d{6})</timestamp>""".r,
      "timestamp",
      metadataFile
    )
    val buildNumber = extractSnapshotMetadataValue(
      metadataContents,
      """<buildNumber>(\d+)</buildNumber>""".r,
      "buildNumber",
      metadataFile
    )

    val baseName = snapshotBaseName(artifactId, version, timestamp, buildNumber)
    val artifactFiles = snapshotArtifactBaseFiles(publishedVersionDir, baseName)
    val expectedFiles =
      withChecksumFiles(Vector(rootMetadataFile, metadataFile)) ++ withChecksumFiles(artifactFiles)
    val actualFiles = os.walk(publishedDir).toVector
    val missingFiles = expectedFiles.filterNot(actualFiles.contains)
    assert(missingFiles.isEmpty)

    val checksumTargets = Vector(rootMetadataFile, metadataFile) ++ artifactFiles
    checksumTargets.foreach { file =>
      assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5")
      assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1")
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
    releaseArtifactBaseFiles(dir, s"$artifactId-$version")

  private def releaseRepoDir(
      repoDir: os.Path,
      group: String,
      artifactId: String,
      version: String
  ): os.Path =
    repoDir / s"$group.$artifactId-$version"

  private def releaseGroupPath(group: String): os.SubPath =
    os.SubPath(group.split('.').toIndexedSeq)

  private val snapshotArtifactSuffixes =
    Vector(".jar", "-sources.jar", "-javadoc.jar", ".pom")

  private def releaseArtifactBaseFiles(
      dir: os.Path,
      baseName: String
  ): Vector[os.Path] =
    snapshotArtifactSuffixes.map(suffix => dir / s"$baseName$suffix")

  private def releaseExpectedFiles(
      dir: os.Path,
      baseName: String
  ): Vector[os.Path] =
    releaseArtifactBaseFiles(dir, baseName).flatMap { file =>
      Vector(
        file,
        os.Path(file.toString + ".asc"),
        os.Path(file.toString + ".asc.md5"),
        os.Path(file.toString + ".asc.sha1"),
        os.Path(file.toString + ".md5"),
        os.Path(file.toString + ".sha1")
      )
    }

  private def snapshotBaseName(
      artifactId: String,
      version: String,
      timestamp: String,
      buildNumber: String
  ): String =
    s"$artifactId-${version.stripSuffix("-SNAPSHOT")}-$timestamp-$buildNumber"

  private def snapshotArtifactBaseFiles(dir: os.Path, baseName: String): Vector[os.Path] =
    snapshotArtifactSuffixes.map(suffix => dir / s"$baseName$suffix")

  private def withChecksumFiles(files: Vector[os.Path]): Vector[os.Path] =
    files.flatMap(file =>
      Vector(file, os.Path(file.toString + ".md5"), os.Path(file.toString + ".sha1"))
    )

  private def extractSnapshotMetadataValue(
      metadataContents: String,
      regex: scala.util.matching.Regex,
      label: String,
      metadataFile: os.Path
  ): String =
    regex.findFirstMatchIn(metadataContents).map(_.group(1)).getOrElse {
      throw new Exception(
        s"No $label found via $regex in $metadataFile:\n$metadataContents"
      )
    }
}
