package mill.scalalib.dependency.updates

import mill.scalalib.JavaModule
import mill.scalalib.dependency.versions.Version

import scala.collection.SortedSet

private[dependency] case class DependencyUpdates(
    module: JavaModule,
    dependencies: Seq[DependencyUpdate])

private[dependency] case class DependencyUpdate(dependency: coursier.Dependency,
                                                currentVersion: Version,
                                                updates: SortedSet[Version])
