package mill.scalaplugin.publish

import mill.util.JsonFormatters

trait Artifact extends Serializable with Product {
  def group: String
  def name: String
  def id: String
  def version: String
}

case class JavaArtifact(
  group: String,
  name: String,
  version: String
) extends Artifact {
  override def id: String = name
}

case class ScalaArtifact(
  group: String,
  name: String,
  version: String,
  scalaVersion: String,
) extends Artifact {

  override def id: String = s"${name}_$scalaVersion"
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

case class License(
  name: String,
  url: String,
  distribution: String = "repo"
)

case class SCM(
  url: String,
  connection: String
)

case class Developer(
  id: String,
  name: String,
  url: String,
  organization: String,
  organizationUrl: String
)

case class PomSettings(
  organization: String,
  url: String,
  licenses: Seq[License],
  scm: SCM,
  developers: Seq[Developer]
)

//trait PublishSettingsJsonFormatters {
//
//  implicit lazy val licenseFormat: upickle.default.ReadWriter[License]= upickle.default.macroRW
//  implicit lazy val scmFormat: upickle.default.ReadWriter[SCM]= upickle.default.macroRW
//  implicit lazy val devFormat: upickle.default.ReadWriter[Developer]= upickle.default.macroRW
//  implicit lazy val pomSettingsFormat: upickle.default.ReadWriter[PomSettings]= upickle.default.macroRW
//}

