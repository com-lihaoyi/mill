package mill.javalib

package object publish extends mill.scalalib.publish.JsonFormatters {
  val Ivy = mill.scalalib.publish.Ivy

  type JsonFormatters = mill.scalalib.JsonFormatters
  val JsonFormatters = mill.scalalib.JsonFormatters

  type License = mill.scalalib.publish.License
  val License = mill.scalalib.publish.License

  type LocalIvyPublisher = mill.scalalib.publish.LocalIvyPublisher
  val LocalIvyPublisher = mill.scalalib.publish.LocalIvyPublisher

  type LocalM2Publisher = mill.scalalib.publish.LocalM2Publisher

  val Pom = mill.scalalib.publish.Pom

  type PublishInfo = mill.scalalib.publish.PublishInfo
  val PublishInfo = mill.scalalib.publish.PublishInfo

  type Artifact = mill.scalalib.publish.Artifact
  val Artifact = mill.scalalib.publish.Artifact

  type Scope = mill.scalalib.publish.Scope
  val Scope = mill.scalalib.publish.Scope

  type Dependency = mill.scalalib.publish.Dependency
  val Dependency = mill.scalalib.publish.Dependency

  type Developer = mill.scalalib.publish.Developer
  val Developer = mill.scalalib.publish.Developer

  type PomSettings = mill.scalalib.publish.PomSettings
  val PomSettings = mill.scalalib.publish.PomSettings

  val PackagingType = mill.scalalib.publish.PackagingType

  val SonatypeHelpers = mill.scalalib.publish.SonatypeHelpers
  type SonatypeHttpApi = mill.scalalib.publish.SonatypeHttpApi
  type SonatypePublisher = mill.scalalib.publish.SonatypePublisher

  type VersionControl = mill.scalalib.publish.VersionControl
  val VersionControl = mill.scalalib.publish.VersionControl

  val VersionControlConnection = mill.scalalib.publish.VersionControlConnection

  type VersionScheme = mill.scalalib.publish.VersionScheme
  val VersionScheme = mill.scalalib.publish.VersionScheme
}
