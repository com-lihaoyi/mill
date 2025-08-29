package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import org.apache.maven.model.*

import scala.jdk.CollectionConverters.*

@mainargs.main
case class MavenBuildGenArgs(
    @mainargs.arg(short = 't')
    testModuleName: String = "test",
    @mainargs.arg(short = 'u')
    unify: mainargs.Flag,
    @mainargs.arg(short = 'o')
    publishProperties: mainargs.Flag,
    metaBuild: MetaBuildArgs,
    cacheRepository: mainargs.Flag,
    processPlugins: mainargs.Flag
) extends ModelerConfig

/**
 * @see [[https://maven.apache.org/download.cgi JDK compatibility]]
 */
object MavenBuildGenMain {

  def main(args: Array[String]): Unit = {
    val args0 = ParserForClass[MavenBuildGenArgs].constructOrExit(args.toSeq)
    import args0.*
    println("converting Maven build")
    val modeler = Modeler(args0)
    val segmentsModels = Tree.from(os.sub): sub =>
      val model = modeler(os.pwd / sub)
      ((sub.segments, model), model.getModules.iterator.asScala.map(s => sub / os.SubPath(s)).toSeq)
    val segmentsByGav = segmentsModels.iterator.map((segments, model) =>
      ((model.getGroupId, model.getArtifactId, model.getVersion), segments)
    ).toMap
    def gav(dep: Dependency) = (dep.getGroupId, dep.getArtifactId, dep.getVersion)

    val modules = segmentsModels.iterator.map: (segments, model) =>
      def mvnDeps(scopes: String*) = model.getDependencies.iterator.asScala.collect:
        case dep if scopes.contains(dep.getScope) && !segmentsByGav.contains(gav(dep)) =>
          toMvnDep(dep)
      .filterNot(JavaModuleConfig.isBomMvnDep).toSeq
      def bomMvnDeps(scopes: String*) = model.getDependencies.iterator.asScala.collect:
        case dep if scopes.contains(dep.getScope) && !segmentsByGav.contains(gav(dep)) =>
          toMvnDep(dep)
      .filter(JavaModuleConfig.isBomMvnDep).toSeq
      def moduleDeps(scopes: String*) = model.getDependencies.iterator.asScala
        .collect:
          case dep if scopes.contains(dep.getScope) => gav(dep)
        .collect(segmentsByGav)
        .map(JavaModuleConfig.ModuleDep(_))
        .toSeq

      val moduleDir = os.pwd / segments
      val testModule = if (os.exists(moduleDir / "src/test")) {
        // "provided" scope is for both compilation and testing
        val testDeps = mvnDeps("test", "provided")
        TestModuleRepr.mixinAndMandatoryMvnDeps(testDeps).map: (mixin, mandatoryMvnDeps) =>
          TestModuleRepr(
            name = testModuleName,
            supertypes = Seq("MavenTests"),
            mixins = Seq(mixin),
            configs = Seq(JavaModuleConfig(
              mandatoryMvnDeps = mandatoryMvnDeps,
              mvnDeps = testDeps.diff(mandatoryMvnDeps),
              bomMvnDeps = bomMvnDeps("test"),
              moduleDeps = moduleDeps("test", "provided")
            ))
          )
      } else None

      val (javacOptions, errorProneModuleConfig) = ErrorProneModuleConfig.javacOptionsAndConfig(
        Plugins.javacOptions(model),
        Plugins.annotationProcessorMvnDeps(model) // TODO Filter known error-prone specific deps
      )
      val javaModuleConfig = JavaModuleConfig(
        mvnDeps = mvnDeps("compile"),
        compileMvnDeps = mvnDeps("provided"),
        runMvnDeps = mvnDeps("runtime"),
        bomMvnDeps = bomMvnDeps("compile"),
        moduleDeps = moduleDeps("compile"),
        compileModuleDeps = moduleDeps("provided"),
        runModuleDeps = moduleDeps("runtime"),
        javacOptions = javacOptions
      )
      val publishModuleConfig = Option.when(!Plugins.skipDeploy(model)):
        PublishModuleConfig(
          pomPackagingType = toPomPublishingType(model.getPackaging),
          pomParentProject = toPomParentProject(model.getParent),
          pomSettings = toPomSettings(model),
          publishVersion = model.getVersion,
          artifactMetadata = toArtifactMetadata(model),
          publishProperties =
            if (publishProperties.value) model.getProperties.asScala.toMap else Map()
        )
      val javaHomeModuleConfig = Plugins.jvmId(model).map(JavaHomeModuleConfig(_))
      val coursierModuleConfig =
        model.getRepositories.iterator.asScala.collect:
          case repo if repo.getId != "central" => repo.getUrl
        .toSeq match {
          case Nil => None
          case repositories => Some(CoursierModuleConfig(repositories))
        }

      ModuleRepr(
        segments = segments,
        supertypes = Seq("MavenModule") ++
          (if (publishModuleConfig.isEmpty) Nil else Seq("PublishModule")) ++
          (if (errorProneModuleConfig.isEmpty) Nil else Seq("ErrorProneModule")),
        configs = javaModuleConfig +: Seq(
          publishModuleConfig,
          javaHomeModuleConfig,
          coursierModuleConfig,
          errorProneModuleConfig
        ).flatten,
        testModule = testModule
      )
    end modules

    var build = BuildRepr.fill(modules.map(Tree(_)).toSeq)
    build = build.withMetaBuild(metaBuild)
    if (unify.value) build = build.unified
    BuildWriter(build).writeFiles()
  }

  def toMvnDep(dep: Dependency) =
    import dep.*
    JavaModuleConfig.mvnDep(
      getGroupId,
      getArtifactId,
      getVersion,
      // prevent interpolation in dynamic values such as ${os.detected.name}
      Option(getClassifier).map(_.replace("$", "$$")),
      Option(getType),
      getExclusions.asScala.map(x => (x.getGroupId, x.getArtifactId))
    )

  def toPomPublishingType(tpe: String) =
    if (tpe == "jar") null else tpe

  def toPomParentProject(parent: Parent) = Option.when(parent != null) {
    import parent.*
    PublishModuleConfig.Artifact(getGroupId, getArtifactId, getVersion)
  }

  def toPomSettings(model: Model) = {
    import model.*
    PublishModuleConfig.PomSettings(
      description = getDescription,
      organization = if (getOrganization == null) null else getOrganization.getName,
      url = getUrl,
      licenses = getLicenses.iterator.asScala.map(toLicense).toSeq,
      versionControl = toVersionControl(getScm),
      developers = getDevelopers.iterator.asScala.map(toDeveloper).toSeq
    )
  }

  def toLicense(license: License) =
    import license.*
    PublishModuleConfig.License(
      name = getName,
      url = getUrl,
      distribution = getDistribution
    )

  def toVersionControl(scm: Scm) = if (scm == null) PublishModuleConfig.VersionControl()
  else
    import scm.*
    PublishModuleConfig.VersionControl(
      browsableRepository = Option(getUrl),
      connection = Option(getConnection),
      developerConnection = Option(getDeveloperConnection),
      tag = Option(getTag)
    )

  def toDeveloper(developer: Developer) =
    import developer.*
    PublishModuleConfig.Developer(
      id = getId,
      name = getName,
      url = getUrl,
      organization = Option(getOrganization),
      organizationUrl = Option(getOrganizationUrl)
    )

  def toArtifactMetadata(model: Model) =
    import model.*
    PublishModuleConfig.Artifact(getGroupId, getArtifactId, getVersion)
}
