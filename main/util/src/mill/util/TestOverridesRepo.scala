package mill.util

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
private final class TestOverridesRepo(root: os.ResourcePath) extends Repository {

  private val map = new ConcurrentHashMap[Module, Option[String]]

  private def listFor(mod: Module): Either[os.ResourceNotFoundException, String] = {

    def entryPath = root / s"${mod.organization.value}-${mod.name.value}"

    val inCacheOpt = Option(map.get(mod))

    inCacheOpt
      .getOrElse {

        val computedOpt =
          try Some(os.read(entryPath))
          catch {
            case _: os.ResourceNotFoundException =>
              None
          }
        val concurrentOpt = Option(map.putIfAbsent(mod, computedOpt))
        concurrentOpt.getOrElse(computedOpt)
      }
      .toRight {
        new os.ResourceNotFoundException(entryPath)
      }
  }

  def find[F[_]: Monad](
      module: Module,
      version: String,
      fetch: Repository.Fetch[F]
  ): EitherT[F, String, (ArtifactSource, Project)] =
    EitherT.fromEither[F] {
      listFor(module)
        .left.map(e => s"No test override found at ${e.path}")
        .map { _ =>
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
          (this, proj)
        }
    }

  def artifacts(
      dependency: Dependency,
      project: Project,
      overrideClassifiers: Option[Seq[Classifier]]
  ): Seq[(Publication, Artifact)] =
    listFor(project.module)
      .toTry.get
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
