package mill.main.maven

import org.apache.maven.model.Model
import org.apache.maven.model.building.{
  DefaultModelBuilderFactory,
  DefaultModelBuildingRequest,
  ModelBuilder
}
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.supplier.RepositorySystemSupplier

import java.io.File
import java.util.Properties

/**
 * Builds a [[Model]].
 *
 * The implementation is inspired by [[https://github.com/sbt/sbt-pom-reader/ sbt-pom-reader]].
 */
class Modeler(
    builder: ModelBuilder,
    resolver: ModelResolver,
    systemProperties: Properties
) {

  /** Builds and returns the effective [[Model]] from `pomDir / "pom.xml"`. */
  def read(pomDir: os.Path): Model =
    read((pomDir / "pom.xml").toIO)

  /** Builds and returns the effective [[Model]] from `pomFile`. */
  def read(pomFile: File): Model = {
    val request = new DefaultModelBuildingRequest()
    request.setPomFile(pomFile)
    request.setModelResolver(resolver.newCopy())
    request.setSystemProperties(systemProperties)

    val result = builder.build(request)
    result.getEffectiveModel
  }
}
object Modeler {

  def apply(
      local: LocalRepository = defaultLocalRepository,
      remotes: Seq[RemoteRepository] = defaultRemoteRepositories,
      context: String = "",
      systemProperties: Properties = defaultSystemProperties
  ): Modeler = {
    val builder = new DefaultModelBuilderFactory().newInstance()
    val system = new RepositorySystemSupplier().get()
    val session = MavenRepositorySystemUtils.newSession()
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local))
    val resolver = new Resolver(system, session, remotes, context)
    new Modeler(builder, resolver, systemProperties)
  }

  def defaultLocalRepository: LocalRepository =
    new LocalRepository((os.home / ".m2/repository").toIO)

  def defaultRemoteRepositories: Seq[RemoteRepository] =
    Seq(
      new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
        .build()
    )

  def defaultSystemProperties: Properties = {
    val props = new Properties()
    sys.env.foreachEntry((k, v) => props.put(s"env.$k", v))
    sys.props.foreachEntry(props.put)
    props
  }
}
