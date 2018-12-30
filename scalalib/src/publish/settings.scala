package mill.scalalib.publish

import mill.scalalib.Dep

case class Artifact(group: String, id: String, version: String) {
  def isSnapshot: Boolean = version.endsWith("-SNAPSHOT")
}

object Artifact {
  def fromDepJava(dep: Dep) = {
    assert(dep.cross.isConstant, s"Not a Java dependency: $dep")
    fromDep(dep, "", "", "")
  }

  def fromDep(dep: Dep,
              scalaFull: String,
              scalaBin: String,
              platformSuffix: String): Dependency = {
    val name = dep.artifactName(
      binaryVersion = scalaBin,
      fullVersion = scalaFull,
      platformSuffix = platformSuffix
    )
    Dependency(
      Artifact(
        dep.dep.module.organization,
        name,
        dep.dep.version
      ),
      Scope.Compile,
      if (dep.dep.configuration == "") None else Some(dep.dep.configuration),
      dep.dep.exclusions.toList
    )
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
    scope: Scope,
    configuration: Option[String] = None,
    exclusions: Seq[(String, String)] = Nil
)

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
