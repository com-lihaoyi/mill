package millbuild

object Settings {
  val pomOrg = "com.lihaoyi"
  val githubOrg = "com-lihaoyi"
  val githubRepo = "mill"
  val projectUrl = s"https://github.com/${githubOrg}/${githubRepo}"
  val changelogUrl = s"${projectUrl}#changelog"
  val newIssueUrl = s"${projectUrl}/issues/new/choose"
  val docUrl = "https://mill-build.org"
  // the exact branches containing a doc root
  val docBranches = Seq()
  // the exact tags containing a doc root. Publish docs for
  // the last point version in each minor release series
  val legacyDocTags: Seq[String] = Seq(
    "0.9.12",
    "0.10.15"
  )
  val docTags: Seq[String] = Seq(
    "0.11.13",
    "0.12.9"
  )
  val mimaBaseVersions: Seq[String] =
    0.to(13).map("0.11." + _) ++
      0.to(9).map("0.12." + _)

  val graalvmJvmId = "graalvm-community:23.0.1"
}
