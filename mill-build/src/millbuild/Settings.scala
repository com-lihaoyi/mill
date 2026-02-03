package millbuild

object Settings {
  val pomOrg = "com.lihaoyi"
  val githubOrg = "com-lihaoyi"
  val githubRepo = "mill"
  val projectUrl = s"https://github.com/${githubOrg}/${githubRepo}"
  val changelogUrl = s"${projectUrl}#changelog"
  val newIssueUrl = s"${projectUrl}/issues/new/choose"
  val docUrl = "https://mill-build.org"
  val mavenRepoUrl = "https://repo1.maven.org/maven2"

  // the exact tags containing a doc root. Publish docs for
  // the last point version in each minor release series
  val legacyDocTags: Seq[String] = Seq(
    "0.9.12",
    "0.10.15"
  )
  val docTags: Seq[String] = Seq(
    "0.11.13",
    "0.12.17",
    "1.0.6",
    "1.1.1"
  )
  val mimaBaseVersions: Seq[String] =
    Seq("1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6", "1.1.0", "1.1.1")

  val graalvmJvmId = "graalvm-community:23.0.1"
}
