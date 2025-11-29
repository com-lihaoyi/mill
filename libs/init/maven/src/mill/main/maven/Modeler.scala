package mill.main.maven

import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.supplier.RepositorySystemSupplier

import java.io.File
import java.util.Properties
import scala.jdk.CollectionConverters.*

class Modeler(builder: ModelBuilder, resolver: ModelResolver, systemProperties: Properties) {

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
    val request = new DefaultModelBuildingRequest()
    request.setPomFile(pomFile)
    request.setModelResolver(resolver)
    request.setSystemProperties(systemProperties)
    request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
    request.setTwoPhaseBuilding(true)
    try {
      val result1 = builder.build(request)
      val depMgmt1 = Option(result1.getEffectiveModel.getDependencyManagement).map(_.clone)
      val result2 = builder.build(request, result1)
      // Restore dep mgmt from Phase 1 since Phase 2 substitutes BOM deps with their components.
      depMgmt1.foreach(result2.getEffectiveModel.setDependencyManagement)
      result2
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
    val props = new Properties()
    System.getenv().forEach((k, v) => props.put(s"env.$k", v))
    System.getProperties.forEach((k, v) => props.put(k, v))
    props
  }
}
