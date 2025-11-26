package mill.contrib.sbom

import coursier.{Fetch, Resolution, VersionConstraint, core as cs}
import mill.Task
import mill.javalib.{BoundDep, JavaModule}

/**
 * Report the Java/Scala/Kotlin dependencies in a SBOM.
 * By default, it reports all dependencies in the [[mvnDeps]] and [[runMvnDeps]].
 * Other scopes and unmanaged dependencies are not added to the report.
 *
 * Change this behavior by  overriding [[sbomComponents]]
 */
trait CycloneDXJavaModule extends JavaModule with CycloneDXModule {
  import CycloneDX.*

  /**
   * Lists of all components used for this module.
   * By default, uses the [[mvnDeps]] and [[runMvnDeps]] for the list of components
   */
  def sbomComponents: Task[Seq[Component]] = Task {
    val resolved = resolvedRunMvnDepsDetails()()
    resolvedSbomComponents(resolved)
  }

  protected def resolvedSbomComponents(resolved: Fetch.Result): Seq[Component] = {
    val distinctDeps = resolved.fullDetailedArtifacts0
      .flatMap {
        case (dep, _, _, Some(path)) => Some(dep -> path)
        case _ => None
      }
      // Artifacts.Result.files does eliminate duplicates path: Do the same
      .distinctBy(_._2)
      .map { case (dep, path) =>
        val license = findLicenses(resolved.resolution, dep.module, dep.versionConstraint)
        Component.fromDeps(os.Path(path), dep, license)
      }
    distinctDeps
  }

  /** Based on [[resolvedRunMvnDeps]], but getting the raw artifacts */
  private def resolvedRunMvnDepsDetails(): Task[Fetch.Result] = Task.Anon {
    millResolver().fetchArtifacts(Seq(
      BoundDep(
        coursierDependency.withConfiguration(cs.Configuration.runtime),
        force = false
      )
    ))
  }

  private def findLicenses(
      resolution: Resolution,
      module: coursier.core.Module,
      version: VersionConstraint
  ): Seq[coursier.Info.License] = {
    val projects = resolution.projectCache0
    val project = projects.get(module -> version)
    project match
      case None => Seq.empty
      case Some((_, proj)) =>
        val licences = proj.info.licenseInfo
        if (licences.nonEmpty) {
          licences
        } else {
          proj.parent0.map((pm, v) =>
            findLicenses(resolution, pm, VersionConstraint.fromVersion(v))
          )
            .getOrElse(Seq.empty)
        }
  }

}
