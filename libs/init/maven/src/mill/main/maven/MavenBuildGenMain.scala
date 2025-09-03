package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import org.apache.maven.model.*

import scala.jdk.CollectionConverters.*

/**
 * Converts a Maven build to Mill by selecting module configurations from POM files.
 * @see [[https://maven.apache.org/download.cgi Maven JDK compatibility]]
 * @see [[MavenBuildGenArgs Command line arguments]]
 */
object MavenBuildGenMain {

  def main(args: Array[String]): Unit = {
    val args0 = ParserForClass[MavenBuildGenArgs].constructOrExit(args.toSeq)
    println("converting Maven build")
    import args0.*

    val modeler = Modeler()
    val segmentsModels = Tree.from(os.sub): sub =>
      val model = modeler.read(os.pwd / sub)
      (
        (sub.segments, model),
        model.getModules.iterator.asScala.map(s => sub / os.SubPath(s)).toSeq
      )
    val segmentsByGav = segmentsModels.iterator.map((segments, model) =>
      ((model.getGroupId, model.getArtifactId, model.getVersion), segments)
    ).toMap
    def gav(dep: Dependency) = (dep.getGroupId, dep.getArtifactId, dep.getVersion)

    val packages = segmentsModels.iterator.map: (segments, model) =>
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
      val testModule0 = if (os.exists(moduleDir / "src/test")) {
        // "provided" scope is for both compilation and testing
        val testDeps = mvnDeps("test", "provided")
        TestModuleRepr.mixinAndMandatoryMvnDeps(testDeps).map: (mixin, mandatoryMvnDeps) =>
          TestModuleRepr(
            name = testModule,
            supertypes = Seq("MavenTests"),
            mixins = Seq(mixin),
            configs = Seq(
              JavaModuleConfig(
                mandatoryMvnDeps = mandatoryMvnDeps,
                mvnDeps = testDeps.diff(mandatoryMvnDeps),
                bomMvnDeps = bomMvnDeps("test"),
                moduleDeps = moduleDeps("test", "provided")
              ),
              RunModuleConfig(
                // Retained from https://github.com/com-lihaoyi/mill/commit/5e650f6b78d903f34e122a9f97c6b223c6251d8f
                forkWorkingDir = "moduleDir"
              )
            ),
            // Retained from https://github.com/com-lihaoyi/mill/commit/ba5960983895565f230166464e66524f0a1a5fd8
            testParallelism = false,
            testSandboxWorkingDir = false
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
        javacOptions = javacOptions.diff(JavaModuleConfig.unsupportedJavacOptions)
      )
      val publishModuleConfig = Option.when(!Plugins.skipDeploy(model)):
        PublishModuleConfig(
          pomPackagingType = Option(model.getPackaging).filter(_ != "jar").orNull,
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
        testModule = testModule0
      )
    .map(Tree(_)).toSeq

    var build = BuildRepr.fill(packages)
    if (merge.value) build = BuildRepr.merged(build)

    val writer = if (noMetaBuild.value) BuildWriter(build)
    else
      val (build0, metaBuild) = MetaBuildRepr.of(build)
      BuildWriter(build0, Some(metaBuild))
    writer.writeFiles()
  }

  def toMvnDep(dep: Dependency) = {
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
  }

  def toPomParentProject(parent: Parent) = {
    Option.when(parent != null):
      import parent.*
      PublishModuleConfig.Artifact(getGroupId, getArtifactId, getVersion)
  }

  def toPomSettings(model: Model) = {
    import model.*
    PublishModuleConfig.PomSettings(
      description = getDescription,
      organization = Option(getOrganization).fold(null)(_.getName),
      url = getUrl,
      licenses = getLicenses.iterator.asScala.map(toLicense).toSeq,
      versionControl = toVersionControl(getScm),
      developers = getDevelopers.iterator.asScala.map(toDeveloper).toSeq
    )
  }

  def toLicense(license: License) = {
    import license.*
    PublishModuleConfig.License(
      name = getName,
      url = getUrl,
      distribution = getDistribution
    )
  }

  def toVersionControl(scm: Scm) = {
    if (scm == null) PublishModuleConfig.VersionControl()
    else
      import scm.*
      PublishModuleConfig.VersionControl(
        browsableRepository = Option(getUrl),
        connection = Option(getConnection),
        developerConnection = Option(getDeveloperConnection),
        tag = Option(getTag)
      )
  }

  def toDeveloper(developer: Developer) = {
    import developer.*
    PublishModuleConfig.Developer(
      id = getId,
      name = getName,
      url = getUrl,
      organization = Option(getOrganization),
      organizationUrl = Option(getOrganizationUrl)
    )
  }

  def toArtifactMetadata(model: Model) = {
    import model.*
    PublishModuleConfig.Artifact(getGroupId, getArtifactId, getVersion)
  }
}

@mainargs.main
case class MavenBuildGenArgs(
    @mainargs.arg(doc = "name of generated test module")
    testModule: String = "test",
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "copy properties for publish")
    publishProperties: mainargs.Flag,
    @mainargs.arg(doc = "disables generating meta-build")
    noMetaBuild: mainargs.Flag
)
