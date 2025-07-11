package mill.javalib.dependency.versions

private[dependency] final case class ModuleDependenciesVersions(
    modulePath: String,
    dependencies: Seq[DependencyVersions]
)

private[dependency] final case class DependencyVersions(
    dependency: coursier.Dependency,
    currentVersion: Version,
    allversions: Set[Version]
)
