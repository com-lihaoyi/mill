package mill.util

import coursier.Repository

import mill.api.{BuildInfo, PathRef, Result}

import java.nio.file.{Files, Paths}

private[mill] object MillModuleUtil {

  /**
   * Deprecated helper method, intended to allow runtime resolution and in-development-tree testings of mill plugins possible.
   * This design has issues and will probably be replaced.
   */
  @deprecated("Use Dep.millProjectModule instead", "Mill 0.13.0-M1")
  private[mill] def millProjectModule(
      artifact: String,
      repositories: Seq[Repository],
      // this should correspond to the mill runtime Scala version
      artifactSuffix: String = "_3"
  ): Result[Seq[PathRef]] = {

    mill.util.Jvm.resolveDependencies(
      repositories = repositories,
      deps = Seq(
        coursier.Dependency(
          coursier.Module(
            coursier.Organization("com.lihaoyi"),
            coursier.ModuleName(artifact + artifactSuffix)
          ),
          coursier.VersionConstraint(BuildInfo.millVersion)
        )
      ),
      force = Nil,
      checkGradleModules = false
    ).map(_.map(_.withRevalidateOnce))
  }

  private val LongMillProps = new java.util.Properties()

  {
    val millOptionsPath = sys.props("MILL_OPTIONS_PATH")
    if (millOptionsPath != null)
      LongMillProps.load(Files.newInputStream(Paths.get(millOptionsPath)))
  }

  def millProperty(key: String): Option[String] =
    Option(sys.props(key)) // System property has priority
      .orElse(Option(LongMillProps.getProperty(key)))

}
