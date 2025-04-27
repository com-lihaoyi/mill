package mill.scalalib.dependency.updates

import mill.scalalib.dependency.versions.Version

import scala.collection.SortedSet

final case class DependencyUpdates(
    dependency: coursier.Dependency,
    currentVersion: Version,
    updates: SortedSet[Version]
)

object DependencyUpdates {
  import mill.scalalib.JsonFormatters.depFormat

  implicit val rw: upickle.default.ReadWriter[DependencyUpdates] =
    upickle.default.macroRW
}
