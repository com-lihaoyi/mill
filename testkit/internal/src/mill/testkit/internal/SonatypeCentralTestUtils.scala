package mill.testkit.internal

private[mill] object SonatypeCentralTestUtils {

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
    val gpgHomeString =
      if (scala.util.Properties.isWin) gpgHome.toString.replace('\\', '/') else gpgHome.toString
    val gpgEnv = Map("GNUPGHOME" -> gpgHomeString)
    val secretBytes = java.util.Base64.getDecoder.decode(secretKeyBase64)
    os.proc("gpg", "--batch", "--yes", "--import").call(stdin = secretBytes, env = gpgEnv)
    f(gpgEnv)
  }

  def verifySignedArtifacts(
      baseDir: os.Path,
      artifactId: String,
      version: String,
      secretBase64: String
  ): Unit = {
    withGpgHome(secretBase64) { gpgEnv =>
      signedArtifacts(baseDir, artifactId, version).foreach { file =>
        assertSignatureValid(file, gpgEnv)
        assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5", gpgEnv)
        assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1", gpgEnv)
      }
    }
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

  private def releaseGroupPath(group: String): os.SubPath =
    os.SubPath(group.split('.').toIndexedSeq)

  private val snapshotArtifactSuffixes =
    Vector(".jar", "-sources.jar", "-javadoc.jar", ".pom")

  private def signedArtifacts(dir: os.Path, artifactId: String, version: String): Vector[os.Path] =
    releaseArtifactBaseFiles(dir, s"$artifactId-$version")

  private def releaseArtifactBaseFiles(
      dir: os.Path,
      baseName: String
  ): Vector[os.Path] =
    snapshotArtifactSuffixes.map(suffix => dir / s"$baseName$suffix")

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
