package mill.main.maven

import mill.api.daemon.internal.internal
import org.apache.maven.model.building.{FileModelSource, ModelSource}
import org.apache.maven.model.resolution.{ModelResolver, UnresolvableModelException}
import org.apache.maven.model.{Dependency, Parent, Repository}
import org.apache.maven.repository.internal.ArtifactDescriptorUtils
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.{ArtifactRequest, ArtifactResolutionException}
import org.eclipse.aether.{RepositorySystem, RepositorySystemSession}

import scala.jdk.CollectionConverters.*

/**
 * Resolves a POM from its coordinates.
 *
 * The implementation is inspired by [[https://github.com/sbt/sbt-pom-reader/ sbt-pom-reader]].
 */
@internal
class Resolver(
    system: RepositorySystem,
    session: RepositorySystemSession,
    remotes: Seq[RemoteRepository],
    context: String
) extends ModelResolver {
  private var repositories = remotes

  override def resolveModel(groupId: String, artifactId: String, version: String): ModelSource = {
    val artifact = new DefaultArtifact(groupId, artifactId, "", "pom", version)
    val request = new ArtifactRequest(artifact, repositories.asJava, context)
    try {
      val result = system.resolveArtifact(session, request)
      new FileModelSource(result.getArtifact.getFile)
    } catch {
      case e: ArtifactResolutionException =>
        throw new UnresolvableModelException(e.getMessage, groupId, artifactId, version, e)
    }
  }

  override def resolveModel(parent: Parent): ModelSource =
    resolveModel(parent.getGroupId, parent.getArtifactId, parent.getVersion)

  override def resolveModel(dependency: Dependency): ModelSource =
    resolveModel(dependency.getGroupId, dependency.getArtifactId, dependency.getVersion)

  override def addRepository(repository: Repository): Unit =
    addRepository(repository, replace = false)

  override def addRepository(repository: Repository, replace: Boolean): Unit = {
    val exists = repositories.exists(_.getId == repository.getId)
    if (!exists || replace) {
      val remote = ArtifactDescriptorUtils.toRemoteRepository(repository)
      repositories = repositories :+ remote
    }
  }

  override def newCopy(): ModelResolver =
    new Resolver(system, session, repositories, context)
}
