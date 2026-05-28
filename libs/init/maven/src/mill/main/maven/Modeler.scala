package mill.main.maven

import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.supplier.RepositorySystemSupplier

import java.io.File
import java.util.Properties
import scala.jdk.CollectionConverters.*

class Modeler(
    mvnWorkspace: os.Path,
    builder: ModelBuilder,
    resolver: ModelResolver,
    systemProperties: Properties
) {

  /** Returns the [[ModelBuildingResult]] for all projects in `workspace`. */
  def buildAll(): Seq[ModelBuildingResult] = {
    def recurse(dir: os.Path): Seq[ModelBuildingResult] = {
      // Pass Maven an un-relativized absolute File. On reproducible-2 `.toIO`
      // returns `../mill-workspace/pom.xml`; Maven stores that relative path
      // verbatim, and `model.getProjectDirectory` then yields a relative File
      // that callers convert via `os.Path(_)` (resolved against `os.pwd`)
      // into a path that is no longer under `mvnWorkspace`, breaking
      // `subRelativeTo` with "ups must be zero, but it is 1 in ../mill-workspace".
      val result = build((dir / "pom.xml").wrapped.toAbsolutePath.normalize().toFile)
      val subResults = result.getEffectiveModel.getModules.asScala.flatMap(rel =>
        recurse(dir / os.RelPath(rel))
      ).toSeq
      result +: subResults
    }
    recurse(mvnWorkspace)
  }

  /** Returns the [[ModelBuildingResult]] for `pomFile`. */
  def build(pomFile: File): ModelBuildingResult = {
    val request = DefaultModelBuildingRequest()
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
      mvnWorkspace: os.Path,
      local: LocalRepository = defaultLocalRepository,
      remotes: Seq[RemoteRepository] = defaultRemoteRepositories,
      context: String = "",
      systemProperties: Properties = null
  ): Modeler = {
    val builder = DefaultModelBuilderFactory().newInstance()
    val system = RepositorySystemSupplier().get()
    val session = MavenRepositorySystemUtils.newSession()
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local))
    val resolver = Resolver(system, session, remotes, context)
    val properties = Option(systemProperties).getOrElse(defaultSystemProperties(mvnWorkspace))
    new Modeler(mvnWorkspace, builder, resolver, properties)
  }

  def defaultLocalRepository: LocalRepository =
    // Real absolute path, not the `../mill-home/...` relativized form `.toIO`
    // would yield on reproducible-2 — Maven's repository layer treats it as a
    // path string verbatim.
    LocalRepository((os.home / ".m2/repository").wrapped.toAbsolutePath.normalize().toFile)

  def defaultRemoteRepositories: Seq[RemoteRepository] =
    Seq(
      RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
        .build()
    )

  def defaultSystemProperties(mvnWorkspace: os.Path): Properties = {
    val props = Properties()
    System.getenv().forEach((k, v) => props.put(s"env.$k", v))
    System.getProperties.forEach((k, v) => props.put(k, v))
    props.put(
      "maven.multiModuleProjectDirectory",
      mvnWorkspace.wrapped.toAbsolutePath.normalize().toString
    )
    props
  }
}
