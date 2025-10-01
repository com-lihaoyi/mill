package mill.main.maven

import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.supplier.RepositorySystemSupplier

import java.io.File
import java.util.Properties
import scala.jdk.CollectionConverters.*

/**
 * The implementation is inspired by [[https://github.com/sbt/sbt-pom-reader/ sbt-pom-reader]].
 */
class Modeler(
    builder: ModelBuilder,
    resolver: ModelResolver,
    systemProperties: Properties
) {

  /** Returns the [[ModelBuildingResult]] for `pomDir / "pom.xml"`. */
  def read(pomDir: os.Path): ModelBuildingResult =
    read((pomDir / "pom.xml").toIO)

  /** Returns the [[ModelBuildingResult]] for `pomFile`. */
  def read(pomFile: File): ModelBuildingResult = {
    val request = new DefaultModelBuildingRequest()
    request.setPomFile(pomFile)
    request.setModelResolver(resolver.newCopy())
    request.setSystemProperties(systemProperties)

    try {
      builder.build(request)
    } catch {
      case e: ModelBuildingException =>
        e.getProblems.asScala.foreach(problem => println(s"ignoring $problem"))
        e.getResult
    }
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
    val props = new Properties(System.getProperties)
    System.getenv().forEach((k, v) => props.put(s"env.$k", v))
    props
  }
}
