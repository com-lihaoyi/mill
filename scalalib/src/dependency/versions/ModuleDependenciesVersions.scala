package mill.scalalib.dependency.versions

import mill.scalalib.JavaModule

final private[dependency] case class ModuleDependenciesVersions(
    modulePath: String,
    dependencies: Seq[DependencyVersions]
)

final private[dependency] case class DependencyVersions(
    dependency: coursier.Dependency,
    currentVersion: Version,
    allversions: Set[Version]
)
