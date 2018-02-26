package mill.scalalib.publish

import mill.scalalib.Dep

case class Artifact(group: String, id: String, version: String) {
  def isSnapshot: Boolean = version.endsWith("-SNAPSHOT")
}

object Artifact {

  def fromDep(dep: Dep,
              scalaFull: String,
              scalaBin: String): Dependency = {
    dep match {
      case Dep.Java(dep, cross) =>
        Dependency(
          Artifact(dep.module.organization, dep.module.name, dep.version),
          Scope.Compile
        )
      case Dep.Scala(dep, cross) =>
        Dependency(
          Artifact(
            dep.module.organization,
            s"${dep.module.name}_${scalaBin}",
            dep.version
          ),
          Scope.Compile
        )
      case Dep.Point(dep, cross) =>
        Dependency(
          Artifact(
            dep.module.organization,
            s"${dep.module.name}_${scalaFull}",
            dep.version
          ),
          Scope.Compile
        )
    }
  }
}

sealed trait Scope
object Scope {
  case object Compile extends Scope
  case object Provided extends Scope
  case object Runtime extends Scope
  case object Test extends Scope
}

case class Dependency(
    artifact: Artifact,
    scope: Scope
)

// https://maven.apache.org/pom.html#SCM
/*
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

@deprecated("use VersionControl", "0.1.3")
case class SCM(
    url: String,
    connection: String
)

object VersionControl {
  def github(owner: String, repo: String, tag: Option[String] = None): VersionControl = 
    VersionControl(
      browsableRepository = Some(s"https://github.com/$owner/$repo"),
      connection = Some(VersionControlConnection.gitGit("github.com", "$owner/$repo.git")),
      developerConnection = Some(VersionControlConnection.gitSsh("github.com", "$owner/$repo.git")),
      tag = tag
    )
}

object VersionControlConnection {
  def network(scm: String,
              protocol: String,
              hostname: String,
              path: String,
              username: Option[String] = None,
              password: Option[String] = None,
              port: Option[Int] = None): String = {
    val portPart = port.map(":" + _).getOrElse("")
    val credentials =
      username match {
        case Some(user) =>
          val pass = password.map(":" + _).getOrElse("")
          s"${user}${pass}"
        case None =>
          password match {
            case Some(p) => sys.error(s"no username set for password: $p")
            case _ => ""
          }
      }

    s"${scm}:${protocol}://${credentials}${hostname}${portPart}/$path"
  }

  def file(scm: String, hostname: Option[String], path: String): String = {
    val hostnamePart = hostname.getOrElse("")
    "scm:$scm:file://${hostnamePart}/$path"
  }

  def gitGit(hostname: String,
             path: String,
             port: Option[Int] = None): String = 
    network("git", "git", hostname, path, port = port)

  def gitHttp(hostname: String,
              path: String,
              port: Option[Int] = None): String =
    network("git", "http", hostname, path, port = port)

  def gitHttps(hostname: String,
               path: String,
               port: Option[Int] = None): String =
    network("git", "https", hostname, path, port = port)

  def gitSsh(hostname: String,
             path: String,
             port: Option[Int] = None): String =
    network("git", "ssh", hostname, path, port = port)

  def gitFile(hostname: Option[String], path: String): String =
    file("git", hostname, path)

  def svnSsh(hostname: String,
             path: String,
             username: Option[String],
             port: Option[Int]): String =
    network("svn", "svn+ssh", hostname, path, username, None, port)

  def svnHttp(hostname: String,
              path: String,
              port: Option[Int],
              username: Option[String],
              password: Option[String]): String =
    network("svn", "http", hostname, path, username, password)

  def svnHttps(hostname: String,
               path: String,
               port: Option[Int],
               username: Option[String],
               password: Option[String]): String =
    network("svn", "https", hostname, path, username, password)

  def svnSvn(username: Option[String],
             password: Option[String],
             hostname: String,
             port: Option[Int],
             path: String): String =
    network("svn", "svn", hostname, path, username, password)

  def svnFile(hostname: Option[String], path: String): String =
    file("svn", hostname, path)
}

case class Developer(
    id: String,
    name: String,
    url: String,
    organization: Option[String] = None,
    organizationUrl: Option[String] = None
)

case class PomSettings(
    description: String,
    organization: String,
    url: String,
    licenses: Seq[License],
    versionControl: VersionControl,
    developers: Seq[Developer]
)

object PomSettings {
  @deprecated("use VersionControl instead of SCM", "0.1.3")
  def apply(description: String,
            organization: String,
            url: String,
            licenses: Seq[License],
            scm: SCM,
            developers: Seq[Developer]): PomSettings = {
    PomSettings(
      description = description,
      organization = organization,
      url = url,
      licenses = licenses,
      versionControl = VersionControl(
        browsableRepository = Some(scm.url),
        connection = Some(scm.connection),
        developerConnection = None,
        tag = None
      ),
      developers = developers
    )
  }
}
