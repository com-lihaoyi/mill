package mill.coursierutil

import coursier.core.{ArtifactSource, Extension, Info, Module, Project, Publication}
import coursier.util.{Artifact, EitherT, Monad}
import coursier.{Classifier, Dependency, Repository, Type}

import java.util.concurrent.ConcurrentHashMap

/**
 * A `coursier.Repository` that exposes modules with hard-coded artifact list
 *
 * Used in Mill tests. This exposes internal workers for example, so that these
 * come from the build and not from remote repositories or ~/.ivy2/local. See
 * `MillJavaModule#{testTransitiveDeps,writeLocalTestOverrides}` in the Mill build.
 */
final class TestOverridesRepo() extends Repository {
  def find[F[_]: Monad](
      module: Module,
      version: String,
      fetch: Repository.Fetch[F]
  ): EitherT[F, String, (ArtifactSource, Project)] =
    EitherT.fromEither[F] {
      sys.env.get(
        s"MILL_LOCAL_TEST_OVERRIDE_${module.organization.value}-${module.name.value}"
      ) match {
        case None => Left(s"No test override found for $module")
        case Some(v) =>
          val proj = Project(
            module,
            version,
            dependencies = Nil,
            configurations = Map.empty,
            parent = None,
            dependencyManagement = Nil,
            properties = Nil,
            profiles = Nil,
            versions = None,
            snapshotVersioning = None,
            packagingOpt = None,
            relocated = false,
            actualVersionOpt = None,
            publications = Nil,
            info = Info.empty
          )
          Right((this, proj))
      }
    }

  def artifacts(
      dependency: Dependency,
      project: Project,
      overrideClassifiers: Option[Seq[Classifier]]
  ): Seq[(Publication, Artifact)] =
    sys.env(
      s"MILL_LOCAL_TEST_OVERRIDE_${dependency.module.organization.value}-${dependency.module.name.value}"
    )
      .linesIterator
      .map(os.Path(_))
      .filter(os.exists)
      .map { path =>
        val pub = Publication(
          if (path.last.endsWith(".jar")) path.last.stripSuffix(".jar") else path.last,
          Type.jar,
          Extension.jar,
          Classifier.empty
        )
        val art = Artifact(path.toNIO.toUri.toASCIIString)
        (pub, art)
      }
      .toSeq
}
