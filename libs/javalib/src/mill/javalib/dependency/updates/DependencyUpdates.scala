package mill.javalib.dependency.updates

import mill.javalib.dependency.versions.Version

import scala.collection.SortedSet

final case class DependencyUpdates(
    dependency: coursier.Dependency,
    currentVersion: Version,
    updates: SortedSet[Version]
)

object DependencyUpdates {
  import mill.javalib.JsonFormatters.depFormat

  implicit val rw: upickle.ReadWriter[DependencyUpdates] =
    upickle.macroRW
}
