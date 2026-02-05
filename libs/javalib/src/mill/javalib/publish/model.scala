package mill.javalib.publish

import mill.javalib.Dep
import upickle.ReadWriter as RW
import JsonFormatters.*
case class Artifact(group: String, id: String, version: String) derives RW {
  require(
    !group.contains("/") &&
      !id.contains("/") &&
      !version.contains("/"),
    "Artifact coordinates must not contain `/`s"
  )

  def isSnapshot: Boolean = version.endsWith("-SNAPSHOT")

  override def toString(): String = s"$group:$id:$version"
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
        dep.organization,
        name,
        dep.version
      ),
      Scope.Compile,
      dep.dep.optional,
      if (dep.dep.configuration.isEmpty) None else Some(dep.dep.configuration.value),
      dep.dep.exclusions().toList.map { case (a, b) => (a.value, b.value) }
    )
  }
}

sealed trait Scope derives RW
object Scope {
  case object Compile extends Scope
  case object Provided extends Scope
  case object Runtime extends Scope
  case object Test extends Scope

  /** Maven "import" scope, to refer to BOM modules */
  case object Import extends Scope
}

case class Dependency(
    artifact: Artifact,
    scope: Scope,
    optional: Boolean = false,
    configuration: Option[String] = None,
    exclusions: Seq[(String, String)] = Nil
) derives RW

case class Developer(
    id: String = "",
    name: String,
    url: String = "",
    organization: Option[String] = None,
    organizationUrl: Option[String] = None,
    @com.lihaoyi.unroll email: String = ""
) derives RW

case class PomSettings(
    description: String,
    organization: String,
    url: String,
    licenses: Seq[License],
    versionControl: VersionControl,
    developers: Seq[Developer],
    @deprecated("Value will be ignored. Use PublishModule.pomPackagingType instead", "Mill 0.11.8")
    packaging: String = PackagingType.Jar
) derives RW

object PackagingType {
  val Pom = "pom"
  val Jar = "jar"
  val Aar = "aar"
}
