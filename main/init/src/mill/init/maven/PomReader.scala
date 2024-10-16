package mill.init.maven

import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.{ModelResolver, UnresolvableModelException}
import org.apache.maven.model.{Dependency, Model, Parent, Repository}
import org.apache.maven.repository.internal.{ArtifactDescriptorUtils, MavenRepositorySystemUtils}
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.resolution.{ArtifactRequest, ArtifactResolutionException}
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.{RepositorySystem, RepositorySystemSession}

import java.io.File
import java.util.Properties
import scala.jdk.CollectionConverters.*

/**
 * Parses a POM file to generate a [[Model]].
 *
 * The implementation is inspired by [[https://github.com/sbt/sbt-pom-reader/ sbt-pom-reader]].
 */
private[maven] class PomReader(
    builder: ModelBuilder,
    resolver: ModelResolver,
    systemProperties: Properties
) {

  /** Returns the effective POM [[Model]] from a `pom.xml` file under `pomDir`. */
  def apply(pomDir: os.Path): Model =
    read((pomDir / "pom.xml").toIO)

  /** Returns the effective POM [[Model]] from `pomFile` */
  def read(pomFile: File): Model = {
    val request = new DefaultModelBuildingRequest()
    request.setModelResolver(resolver)
    request.setSystemProperties(systemProperties)
    request.setPomFile(pomFile)

    builder.build(request).getEffectiveModel
  }
}
object PomReader {

  def apply(
      local: LocalRepository = defaultLocalRepository,
      remotes: Seq[RemoteRepository] = defaultRemoteRepositories,
      context: String = "",
      systemProperties: Properties = defaultSystemProperties
  ): PomReader = {
    val builder = new DefaultModelBuilderFactory().newInstance()
    val system = new RepositorySystemSupplier().get()
    val session = MavenRepositorySystemUtils.newSession()
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local))
    new PomReader(builder, resolver(system, session, remotes, context), systemProperties)
  }

  private def defaultLocalRepository: LocalRepository =
    new LocalRepository((os.home / ".m2" / "repository").toIO)

  private def defaultRemoteRepositories: Seq[RemoteRepository] =
    Seq(
      new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
        .build()
    )

  private def defaultSystemProperties: Properties = {
    val props = new Properties()
    sys.env.foreachEntry((k, v) => props.put(s"env.$k", v))
    sys.props.foreachEntry(props.put)
    props
  }

  private def resolver(
      system: RepositorySystem,
      session: RepositorySystemSession,
      remotes: Seq[RemoteRepository],
      context: String
  ): ModelResolver =
    new ModelResolver {
      private[this] var repositories = remotes
      override def resolveModel(
          groupId: String,
          artifactId: String,
          version: String
      ): ModelSource = {
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
          repositories = repositories :+ ArtifactDescriptorUtils.toRemoteRepository(repository)
        }
      }

      override def newCopy(): ModelResolver =
        resolver(system, session, repositories, context)
    }
}
