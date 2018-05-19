package mill.scalalib.dependency.versions

import mill.scalalib.JavaModule

private[dependency] case class DependencyVersions(
    module: JavaModule,
    dependencies: Seq[DependencyVersion])

private[dependency] case class DependencyVersion(
    dependency: coursier.Dependency,
    currentVersion: Version,
    allversions: Set[Version])
