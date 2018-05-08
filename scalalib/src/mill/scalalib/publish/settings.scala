package mill.scalalib.publish

import mill.scalalib.Dep

case class Artifact(group: String, id: String, version: String) {
  def isSnapshot: Boolean = version.endsWith("-SNAPSHOT")
}

object Artifact {
  def fromDepJava(dep: Dep) = {
    dep match {
      case Dep.Java(dep, cross, force) =>
        Dependency(
          Artifact(dep.module.organization, dep.module.name, dep.version),
          Scope.Compile,
          if (dep.configuration == "") None else Some(dep.configuration),
          dep.exclusions.toList
        )
    }
  }
  def fromDep(dep: Dep,
              scalaFull: String,
              scalaBin: String): Dependency = {
    dep match {
      case d: Dep.Java => fromDepJava(d)
      case Dep.Scala(dep, cross, force) =>
        Dependency(
          Artifact(
            dep.module.organization,
            s"${dep.module.name}_${scalaBin}",
            dep.version
          ),
          Scope.Compile,
          if (dep.configuration == "") None else Some(dep.configuration),
          dep.exclusions.toList
        )
      case Dep.Point(dep, cross, force) =>
        Dependency(
          Artifact(
            dep.module.organization,
            s"${dep.module.name}_${scalaFull}",
            dep.version
          ),
          Scope.Compile,
          if (dep.configuration == "") None else Some(dep.configuration),
          dep.exclusions.toList
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
