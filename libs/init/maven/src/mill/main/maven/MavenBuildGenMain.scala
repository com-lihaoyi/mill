package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import mill.main.buildgen.BuildConventions.*
import org.apache.maven.model.*

import scala.jdk.CollectionConverters.*

/**
 * Application that generates Mill build files for a Maven project. This is achieved by mapping each
 * ''pom.xml'' in a workspace to a module with configurations extracted using the Maven POM API.
 */
object MavenBuildGenMain {

  /**
   * @see [[MavenBuildGenArgs Command line arguments]]
   */
  def main(args: Array[String]): Unit = {
    val args0 = summon[ParserForClass[MavenBuildGenArgs]].constructOrExit(args.toSeq)
    import args0.*
    println("converting Maven build")

    val modelBuildingResults = {
      val modeler = Modeler()
      Tree.from(os.sub) { dir =>
        val result = modeler.build(os.pwd / dir)
        (
          (dir.segments, result),
          result.getEffectiveModel.getModules.iterator.asScala.map(dir / os.SubPath(_)).toSeq
        )
      }.iterator.toSeq
    }

    val moduleDepsByGav = modelBuildingResults.iterator.map { (segments, build) =>
      val model = build.getEffectiveModel
      ((model.getGroupId, model.getArtifactId, model.getVersion), ModuleConfig.ModuleDep(segments))
    }.toMap
    val packages = modelBuildingResults.iterator.map { (segments, result) =>
      val moduleDir = os.pwd / segments
      val model = result.getEffectiveModel
      val rawModel = result.getRawModel
      val plugins = Plugins(model)

      def isBom(dep: Dependency) = isBomDep(dep.getGroupId, dep.getArtifactId)
      def deps(scopes: Seq[String]) = model.getDependencies.iterator.asScala
        .filter(dep => scopes.contains(dep.getScope))
      def mvnDeps(scopes: String*) = deps(scopes).collect {
        case dep if !moduleDepsByGav.contains(toGav(dep)) && !isBom(dep) => toMvnDep(dep)
      }.toSeq
      def bomMvnDeps(scopes: String*) = deps(scopes).collect {
        case dep if !moduleDepsByGav.contains(toGav(dep)) && isBom(dep) => toMvnDep(dep)
      }.toSeq
      def moduleDeps(scopes: String*) = deps(scopes).map(toGav).collect(moduleDepsByGav).toSeq

      val coursierModuleConfig = model.getRepositories.iterator.asScala.collect {
        case repo if repo.getId != "central" => repo.getUrl
      }.toSeq match {
        case Nil => None
        case repositories => Some(CoursierModuleConfig(repositories))
      }
      // TODO Filter known error-prone deps
      val errorProneMvnDeps = plugins.javacAnnotationProcessorMvnDeps
      val (errorProneModuleConfig, javacOptions) =
        findErrorProneModuleConfigJavacOptions(plugins.javacOptions, errorProneMvnDeps)
      val javaHomeModuleConfig = findJavaHomeModuleConfig(plugins.javaVersion, javacOptions)
      val javaModuleConfig = JavaModuleConfig(
        mvnDeps = mvnDeps("compile"),
        compileMvnDeps = mvnDeps("provided"),
        runMvnDeps = mvnDeps("runtime"),
        bomMvnDeps = bomMvnDeps("compile"),
        moduleDeps = moduleDeps("compile"),
        compileModuleDeps = moduleDeps("provided"),
        runModuleDeps = moduleDeps("runtime"),
        javacOptions = javacOptions,
        artifactName = overrideArtifactName(model.getArtifactId, segments)
      )
      val publishModuleConfig = Option.when(!plugins.skipDeploy) {
        // When omitted, values like SCM are derived from the POM hierarchy. Using the effective
        // model would make such values explicit in the project POM. So, to reproduce the original
        // with empty values, we use the raw model for conversion.
        PublishModuleConfig(
          pomPackagingType = overridePomPackagingType(rawModel.getPackaging),
          pomParentProject = toPomParentProject(rawModel.getParent),
          pomSettings = toPomSettings(rawModel),
          publishVersion = model.getVersion,
          publishProperties =
            if (publishProperties.value) rawModel.getProperties.asScala.toMap else Map()
        )
      }

      val testModule = if (os.exists(moduleDir / "src/test")) {
        // "provided" scope is for both compilation and testing
        val mvnDeps0 = mvnDeps("test", "provided")
        findTestModuleMixin(mvnDeps0).map { mixin =>
          // ErrorProne is applied to test sources by default
          val testErrorProneModuleConfig = errorProneModuleConfig
          val testConfigs = Seq(
            JavaModuleConfig(
              mvnDeps = mvnDeps0,
              bomMvnDeps = bomMvnDeps("test"),
              moduleDeps = moduleDeps("test", "provided")
            ),
            RunModuleConfig(
              forkWorkingDir = "moduleDir"
            )
          )
          val testSupertypes = "MavenTests" +:
            (if (testErrorProneModuleConfig.isEmpty) Nil else Seq("ErrorProneModule"))
          TestModuleRepr(
            supertypes = testSupertypes,
            mixins = Seq(mixin),
            configs = testConfigs,
            testParallelism = false,
            testSandboxWorkingDir = false
          )
        }
      } else None

      val supertypes = Seq("MavenModule") ++
        Option.when(publishModuleConfig.nonEmpty)("PublishModule") ++
        Option.when(errorProneModuleConfig.nonEmpty)("ErrorProneModule")
      val configs = javaModuleConfig +: Seq(
        publishModuleConfig,
        errorProneModuleConfig,
        javaHomeModuleConfig,
        coursierModuleConfig
      ).flatten
      val module = ModuleRepr(
        segments = segments,
        supertypes = supertypes,
        configs = configs,
        testModule = testModule
      )
      Tree(module)
    }.toSeq

    var build = BuildRepr.fill(packages)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withMetaBuild
    BuildWriter(build).writeFiles()
  }

  def toGav(dep: Dependency) = (dep.getGroupId, dep.getArtifactId, dep.getVersion)

  def toMvnDep(dep: Dependency) = {
    import dep.*
    ModuleConfig.MvnDep(
      organization = getGroupId,
      name = getArtifactId,
      version = Option(getVersion),
      // prevent evaluation of dynamic values, such as ${os.detected.name}, in generated build
      classifier = Option(getClassifier).map(_.replace("$", "$$")),
      `type` = Option(getType),
      excludes = getExclusions.asScala.map(x => (x.getGroupId, x.getArtifactId)).toSeq
    )
  }

  def toPomParentProject(parent: Parent) = {
    Option.when(parent != null):
      import parent.*
      ModuleConfig.Artifact(getGroupId, getArtifactId, getVersion)
  }

  def toPomSettings(model: Model) = {
    import model.*
    ModuleConfig.PomSettings(
      description = getDescription,
      organization = getGroupId,
      url = getUrl,
      licenses = getLicenses.iterator.asScala.map(toLicense).toSeq,
      versionControl = toVersionControl(getScm),
      developers = getDevelopers.iterator.asScala.map(toDeveloper).toSeq
    )
  }

  def toLicense(license: License) = {
    import license.*
    ModuleConfig.License(
      name = getName,
      url = getUrl,
      distribution = getDistribution
    )
  }

  def toVersionControl(scm: Scm) = {
    if (scm == null) ModuleConfig.VersionControl()
    else
      import scm.*
      ModuleConfig.VersionControl(
        browsableRepository = Option(getUrl),
        connection = Option(getConnection),
        developerConnection = Option(getDeveloperConnection),
        tag = Option(getTag)
      )
  }

  def toDeveloper(developer: Developer) = {
    import developer.*
    ModuleConfig.Developer(
      id = getId,
      name = getName,
      url = getUrl,
      organization = Option(getOrganization),
      organizationUrl = Option(getOrganizationUrl)
    )
  }
}

@mainargs.main
case class MavenBuildGenArgs(
    @mainargs.arg(doc = "extract properties for publish")
    publishProperties: mainargs.Flag,
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "disable generating meta-build files")
    noMeta: mainargs.Flag
)
object MavenBuildGenArgs {
  given ParserForClass[MavenBuildGenArgs] = ParserForClass.apply
}
