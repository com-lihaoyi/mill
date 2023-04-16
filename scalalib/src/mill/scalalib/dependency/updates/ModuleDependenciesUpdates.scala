package mill.scalalib.dependency.updates

import mill.scalalib.JavaModule
import mill.scalalib.dependency.versions.Version

import scala.collection.SortedSet

final case class ModuleDependenciesUpdates(
    modulePath: String,
    dependencies: Seq[DependencyUpdates]
)

object ModuleDependenciesUpdates {
  implicit val rw: upickle.default.ReadWriter[ModuleDependenciesUpdates] =
    upickle.default.macroRW
}

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
