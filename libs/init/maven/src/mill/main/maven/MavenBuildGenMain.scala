package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import mill.main.buildgen.ModuleConfig.*
import org.apache.maven.model.{Developer as MvnDeveloper, License as MvnLicense, *}

import scala.jdk.CollectionConverters.*

/**
 * Application that imports a Maven build to Mill.
 */
object MavenBuildGenMain {

  /**
   * @see [[MavenBuildGenArgs Command line arguments]]
   */
  def main(args: Array[String]): Unit = {
    val args0 = summon[ParserForClass[MavenBuildGenArgs]].constructOrExit(args.toSeq)
    import args0.*
    println("converting Maven build")

    val modelBuildingResults = Modeler().buildAll()
    val moduleDepsByGav = modelBuildingResults.map { result =>
      val model = result.getEffectiveModel
      val segments = os.Path(model.getProjectDirectory).relativeTo(os.pwd).segments
      val gav = (model.getGroupId, model.getArtifactId, model.getVersion)
      (gav, ModuleDep(segments))
    }.toMap
    val packages = modelBuildingResults.map { result =>
      val model = result.getEffectiveModel
      val plugins = Plugins(model)
      val moduleDir = os.Path(model.getProjectDirectory)
      val segments = moduleDir.relativeTo(os.pwd).segments

      def isBom(dep: Dependency) = isBomDep(dep.getGroupId, dep.getArtifactId)
      def dependencies(scopes: Seq[String]) = model.getDependencies.asScala
        .filter(dep => scopes.contains(dep.getScope))
      def mvnDeps(scopes: String*) = dependencies(scopes).collect {
        case dep if !moduleDepsByGav.contains(toGav(dep)) && !isBom(dep) => toMvnDep(dep)
      }.toSeq
      def bomMvnDeps(scopes: String*) = dependencies(scopes).collect {
        case dep if !moduleDepsByGav.contains(toGav(dep)) && isBom(dep) => toMvnDep(dep)
      }.toSeq
      def moduleDeps(scopes: String*) =
        dependencies(scopes).map(toGav).collect(moduleDepsByGav).toSeq

      val mainCoursierModule = model.getRepositories.asScala.collect {
        case repo if repo.getId != "central" => repo.getUrl
      }.toSeq match {
        case Nil => None
        case repositories => Some(CoursierModule(repositories))
      }
      // TODO Filter error-prone deps whitelist
      val errorProneMvnDeps = plugins.javacAnnotationProcessorMvnDeps
      val (mainErrorProneModule, mainJavacOptions) =
        ErrorProneModule.find(plugins.javacOptions, errorProneMvnDeps)
      val mainJavaHomeModule = JavaHomeModule.find(plugins.javaVersion, mainJavacOptions)
      val mainJavaModule = JavaModule(
        mvnDeps = mvnDeps("compile"),
        compileMvnDeps = mvnDeps("provided"),
        runMvnDeps = mvnDeps("runtime"),
        bomMvnDeps = bomMvnDeps("compile"),
        moduleDeps = moduleDeps("compile"),
        compileModuleDeps = moduleDeps("provided"),
        runModuleDeps = moduleDeps("runtime"),
        javacOptions = mainJavacOptions,
        artifactName = model.getArtifactId
      )
      val mainPublishModule = Option.when(!plugins.skipDeploy) {
        // Use raw model to avoid publishing any derived values returned by the effective model.
        val rawModel = result.getRawModel
        PublishModule(
          pomPackagingType = PublishModule.pomPackagingTypeOverride(rawModel.getPackaging),
          pomParentProject = toPomParentProject(rawModel.getParent),
          pomSettings = toPomSettings(rawModel),
          publishVersion = model.getVersion,
          publishProperties =
            if (publishProperties.value) rawModel.getProperties.asScala.toMap else Map()
        )
      }

      val testModule = if (os.exists(moduleDir / "src/test"))
        TestModule.mixin(mvnDeps("test")).map { testModuleMixin =>
          // provided dependencies are included in run scope to reproduce Maven behavior
          val testJavaModule = JavaModule(
            mvnDeps = mvnDeps("test"),
            compileMvnDeps = mvnDeps("provided"),
            runMvnDeps = mvnDeps("provided"),
            bomMvnDeps = bomMvnDeps("test"),
            moduleDeps = moduleDeps("test"),
            compileModuleDeps = moduleDeps("provided"),
            runModuleDeps = moduleDeps("provided")
          )
          // ErrorProne is applied to test sources by default
          val testErrorProneModule = mainErrorProneModule
          val testConfigs = Seq(testJavaModule) ++ testErrorProneModule ++
            // reproduce Maven behavior
            Seq(
              RunModule(
                forkWorkingDir = "moduleDir"
              ),
              TestModule(
                testParallelism = "false",
                testSandboxWorkingDir = "false"
              )
            )
          val testSupertypes = "MavenTests" +: testConfigs.collect {
            case _: ErrorProneModule => "ErrorProneModule"
          }
          ModuleSpec(
            name = "test",
            supertypes = testSupertypes,
            mixins = Seq(testModuleMixin),
            configs = testConfigs
          )
        }
      else None

      val mainConfigs = mainJavaModule +: Seq(
        mainErrorProneModule,
        mainJavaHomeModule,
        mainPublishModule,
        mainCoursierModule
      ).flatten
      val mainSupertypes = "MavenModule" +: mainConfigs.collect {
        case _: PublishModule => "PublishModule"
        case _: ErrorProneModule => "ErrorProneModule"
      }
      val mainModule = ModuleSpec(
        name = segments.lastOption.getOrElse(os.pwd.last),
        supertypes = mainSupertypes,
        configs = mainConfigs,
        nestedModules = testModule.toSeq
      )
      PackageSpec(segments, mainModule)
    }

    var build = BuildSpec.fill(packages)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withDefaultMetaBuild
    BuildWriter(build).writeFiles()
  }

  private def toGav(dep: Dependency) = (dep.getGroupId, dep.getArtifactId, dep.getVersion)

  private def toMvnDep(dep: Dependency) = {
    import dep.*
    MvnDep(
      organization = getGroupId,
      name = getArtifactId,
      version = Option(getVersion),
      // prevent evaluation of dynamic values, such as ${os.detected.name}, in generated build
      classifier = Option(getClassifier).map(_.replace("$", "$$")),
      `type` = Option(getType),
      excludes = getExclusions.asScala.map(x => (x.getGroupId, x.getArtifactId)).toSeq
    )
  }

  private def toPomParentProject(parent: Parent) = {
    if (parent == null) null
    else {
      import parent.*
      Artifact(getGroupId, getArtifactId, getVersion)
    }
  }

  private def toPomSettings(model: Model) = {
    import model.*
    PomSettings(
      description = getDescription,
      organization = getGroupId,
      url = getUrl,
      licenses = getLicenses.asScala.map(toLicense).toSeq,
      versionControl = toVersionControl(getScm),
      developers = getDevelopers.asScala.map(toDeveloper).toSeq
    )
  }

  private def toLicense(license: MvnLicense) = {
    import license.*
    License(
      name = getName,
      url = getUrl,
      distribution = getDistribution
    )
  }

  private def toVersionControl(scm: Scm) = {
    if (scm == null) VersionControl()
    else
      import scm.*
      VersionControl(
        browsableRepository = Option(getUrl),
        connection = Option(getConnection),
        developerConnection = Option(getDeveloperConnection),
        tag = Option(getTag)
      )
  }

  private def toDeveloper(developer: MvnDeveloper) = {
    import developer.*
    Developer(
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
