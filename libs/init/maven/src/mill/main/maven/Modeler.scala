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

  /** Returns the [[ModelBuildingResult]] for all projects in `workspace`. */
  def buildAll(workspace: os.Path = os.pwd): Seq[ModelBuildingResult] = {
    def recurse(dir: os.Path): Seq[ModelBuildingResult] = {
      val result = build((dir / "pom.xml").toIO)
      val subResults = result.getEffectiveModel.getModules.asScala.flatMap(rel =>
        recurse(dir / os.RelPath(rel))
      ).toSeq
      result +: subResults
    }
    recurse(workspace)
  }

  /** Returns the [[ModelBuildingResult]] for `pomFile`. */
  def build(pomFile: File): ModelBuildingResult = {
    val request = DefaultModelBuildingRequest()
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
    val builder = DefaultModelBuilderFactory().newInstance()
    val system = RepositorySystemSupplier().get()
    val session = MavenRepositorySystemUtils.newSession()
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local))
    val resolver = Resolver(system, session, remotes, context)
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
    val props = Properties(System.getProperties)
    System.getenv().forEach((k, v) => props.put(s"env.$k", v))
    props
  }
}
