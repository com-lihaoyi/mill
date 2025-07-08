package mill.kotlinlib

package object publish {

  val Ivy = mill.javalib.publish.Ivy

  type License = mill.javalib.publish.License
  val License = mill.javalib.publish.License

  type LocalIvyPublisher = mill.javalib.publish.LocalIvyPublisher
  val LocalIvyPublisher = mill.javalib.publish.LocalIvyPublisher

  type LocalM2Publisher = mill.javalib.publish.LocalM2Publisher

  val Pom = mill.javalib.publish.Pom

  type PublishInfo = mill.javalib.publish.PublishInfo
  val PublishInfo = mill.javalib.publish.PublishInfo

  type Artifact = mill.javalib.publish.Artifact
  val Artifact = mill.javalib.publish.Artifact

  type Scope = mill.javalib.publish.Scope
  val Scope = mill.javalib.publish.Scope

  type Dependency = mill.javalib.publish.Dependency
  val Dependency = mill.javalib.publish.Dependency

  type Developer = mill.javalib.publish.Developer
  val Developer = mill.javalib.publish.Developer

  type PomSettings = mill.javalib.publish.PomSettings
  val PomSettings = mill.javalib.publish.PomSettings

  val PackagingType = mill.javalib.publish.PackagingType

  val SonatypeHelpers = mill.javalib.publish.SonatypeHelpers
  type SonatypeHttpApi = mill.javalib.publish.SonatypeHttpApi
  type SonatypePublisher = mill.javalib.publish.SonatypePublisher

  type VersionControl = mill.javalib.publish.VersionControl
  val VersionControl = mill.javalib.publish.VersionControl

  val VersionControlConnection = mill.javalib.publish.VersionControlConnection

  type VersionScheme = mill.javalib.publish.VersionScheme
  val VersionScheme = mill.javalib.publish.VersionScheme
}
