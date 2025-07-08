package mill.javalib.publish

/**
 * https://maven.apache.org/pom.html#SCM
 *
 * @param browsableRepository: a publicly browsable repository
 *        (example: https://github.com/lihaoyi/mill)
 * @param connection: read-only connection to repository
 *        (example: scm:git:git://github.com/lihaoyi/mill.git)
 * @param developerConnection: read-write connection to repository
 *        (example: scm:git:git@github.com:lihaoyi/mill.git)
 * @param tag: tag that was created for this release. This is useful for
 *        git and mercurial since it's not possible to include the tag in
 *        the connection url.
 *        (example: v2.12.4, HEAD, my-branch, fd8a2567ad32c11bcf8adbaca85bdba72bb4f935, ...)
 */
case class VersionControl(
    browsableRepository: Option[String] = None,
    connection: Option[String] = None,
    developerConnection: Option[String] = None,
    tag: Option[String] = None
)

object VersionControl {
  def github(owner: String, repo: String, tag: Option[String] = None): VersionControl =
    VersionControl(
      browsableRepository = Some(s"https://github.com/$owner/$repo"),
      connection = Some(VersionControlConnection.gitGit("github.com", s"$owner/$repo.git")),
      developerConnection = Some(VersionControlConnection.gitSsh(
        "github.com",
        s":$owner/$repo.git",
        username = Some("git")
      )),
      tag = tag
    )
  def gitlab(owner: String, repo: String, tag: Option[String] = None): VersionControl =
    VersionControl(
      browsableRepository = Some(s"https://gitlab.com/$owner/$repo"),
      connection = Some(VersionControlConnection.gitGit("gitlab.com", s"$owner/$repo.git")),
      developerConnection = Some(VersionControlConnection.gitSsh(
        "gitlab.com",
        s":$owner/$repo.git",
        username = Some("git")
      )),
      tag = tag
    )
}

object VersionControlConnection {
  def network(
      scm: String,
      protocol: String,
      hostname: String,
      path: String,
      username: Option[String] = None,
      password: Option[String] = None,
      port: Option[Int] = None
  ): String = {
    val portPart = port.map(":" + _).getOrElse("")
    val credentials =
      username match {
        case Some(user) =>
          val pass = password.map(":" + _).getOrElse("")
          s"${user}${pass}@"
        case None =>
          password match {
            case Some(p) => sys.error(s"no username set for password: $p")
            case _ => ""
          }
      }

    val path0 =
      if (path.startsWith(":") || path.startsWith("/")) path
      else "/" + path

    s"scm:${scm}:${protocol}://${credentials}${hostname}${portPart}${path0}"
  }

  def file(scm: String, path: String): String = {
    s"scm:$scm:file://$path"
  }

  def gitGit(hostname: String, path: String = "", port: Option[Int] = None): String =
    network("git", "git", hostname, path, port = port)

  def gitHttp(hostname: String, path: String = "", port: Option[Int] = None): String =
    network("git", "http", hostname, path, port = port)

  def gitHttps(hostname: String, path: String = "", port: Option[Int] = None): String =
    network("git", "https", hostname, path, port = port)

  def gitSsh(
      hostname: String,
      path: String = "",
      username: Option[String] = None,
      port: Option[Int] = None
  ): String =
    network("git", "ssh", hostname, path, username = username, port = port)

  def gitFile(path: String): String =
    file("git", path)

  def svnSsh(
      hostname: String,
      path: String = "",
      username: Option[String] = None,
      port: Option[Int] = None
  ): String =
    network("svn", "svn+ssh", hostname, path, username, None, port)

  def svnHttp(
      hostname: String,
      path: String = "",
      username: Option[String] = None,
      password: Option[String] = None,
      port: Option[Int] = None
  ): String =
    network("svn", "http", hostname, path, username, password, port)

  def svnHttps(
      hostname: String,
      path: String = "",
      username: Option[String] = None,
      password: Option[String] = None,
      port: Option[Int] = None
  ): String =
    network("svn", "https", hostname, path, username, password, port)

  def svnSvn(
      hostname: String,
      path: String = "",
      username: Option[String] = None,
      password: Option[String] = None,
      port: Option[Int] = None
  ): String =
    network("svn", "svn", hostname, path, username, password, port)

  def svnFile(path: String): String =
    file("svn", path)
}
