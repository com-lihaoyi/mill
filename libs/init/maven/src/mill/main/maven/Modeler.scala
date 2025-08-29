package mill.main.maven

import org.apache.maven.model.Model
import org.apache.maven.model.building.{
  DefaultModelBuilderFactory,
  DefaultModelBuildingRequest,
  ModelBuilder
}
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositoryCache
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
    config: ModelerConfig,
    builder: ModelBuilder,
    resolver: ModelResolver,
    systemProperties: Properties
) {

  /** Builds and returns the effective [[Model]] from `pomDir / "pom.xml"`. */
  def apply(pomDir: os.Path): Model =
    build((pomDir / "pom.xml").toIO)

  /** Builds and returns the effective [[Model]] from `pomFile`. */
  def build(pomFile: File): Model = {
    val request = new DefaultModelBuildingRequest()
    request.setPomFile(pomFile)
    request.setModelResolver(resolver.newCopy())
    request.setSystemProperties(systemProperties)
    request.setProcessPlugins(config.processPlugins.value)

    val result = builder.build(request)
    result.getEffectiveModel
  }
}
object Modeler {

  def apply(
      config: ModelerConfig,
      local: LocalRepository = defaultLocalRepository,
      remotes: Seq[RemoteRepository] = defaultRemoteRepositories,
      context: String = "",
      systemProperties: Properties = defaultSystemProperties
  ): Modeler = {
    val builder = new DefaultModelBuilderFactory().newInstance()
    val system = new RepositorySystemSupplier().get()
    val session = MavenRepositorySystemUtils.newSession()
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local))
    if (config.cacheRepository.value) session.setCache(new DefaultRepositoryCache)
    val resolver = new Resolver(system, session, remotes, context)
    new Modeler(config, builder, resolver, systemProperties)
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

trait ModelerConfig {
  def cacheRepository: mainargs.Flag
  def processPlugins: mainargs.Flag
}
