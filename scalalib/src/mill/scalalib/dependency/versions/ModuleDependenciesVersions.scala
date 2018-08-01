package mill.scalalib.dependency.versions

import mill.scalalib.JavaModule

private[dependency] final case class ModuleDependenciesVersions(
    module: JavaModule,
    dependencies: Seq[DependencyVersions])

private[dependency] final case class DependencyVersions(
    dependency: coursier.Dependency,
    currentVersion: Version,
    allversions: Set[Version])
