package mill.javalib

object PublishSonatypeCentralTestUtils {
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
