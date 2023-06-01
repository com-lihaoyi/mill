package mill.scalalib.publish

import mill.scalalib.Dep

case class Artifact(group: String, id: String, version: String) {
  require(
    !group.contains("/") &&
      !id.contains("/") &&
      !version.contains("/"),
    "Artifact coordinates must not contain `/`s"
  )
  def isSnapshot: Boolean = version.endsWith("-SNAPSHOT")
}

object Artifact {
  def fromDepJava(dep: Dep): Dependency = {
    assert(dep.cross.isConstant, s"Not a Java dependency: $dep")
    fromDep(dep, "", "", "")
  }

  def fromDep(dep: Dep, scalaFull: String, scalaBin: String, platformSuffix: String): Dependency = {
    val name = dep.artifactName(
      binaryVersion = scalaBin,
      fullVersion = scalaFull,
      platformSuffix = platformSuffix
    )
    Dependency(
      Artifact(
        dep.dep.module.organization.value,
        name,
        dep.dep.version
      ),
      Scope.Compile,
      dep.dep.optional,
      if (dep.dep.configuration.isEmpty) None else Some(dep.dep.configuration.value),
      dep.dep.exclusions().toList.map { case (a, b) => (a.value, b.value) }
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
    optional: Boolean = false,
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
    developers: Seq[Developer],
    packaging: String = "jar"
)
