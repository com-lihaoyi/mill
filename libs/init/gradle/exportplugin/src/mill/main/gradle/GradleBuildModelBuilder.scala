package mill.main.gradle

import mill.main.buildgen.*
import mill.main.buildgen.BuildConventions.*
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.repositories.{ArtifactRepository, UrlArtifactRepository}
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.*
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.tooling.provider.model.{ToolingModelBuilder, ToolingModelBuilderRegistry}

import java.io.File
import javax.inject.Inject
import scala.jdk.CollectionConverters.*

class GradleBuildModelBuilder(ctx: GradleBuildCtx, workspace: os.Path) extends ToolingModelBuilder {

  def canBuild(modelName: String) = classOf[GradleBuildModel].getName == modelName

  def buildAll(modelName: String, project: Project) = {
    val modules = Iterator.iterate(Set(project))(_.flatMap(_.getSubprojects.asScala))
      .takeWhile(_.nonEmpty)
      .flatten
      .map(toModule)
      .toSeq
    new GradleBuildModel.Impl(upickle.default.write(modules))
  }

  def toModule(project0: Project) = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    val segments = moduleDir.subRelativeTo(workspace).segments

    def isBom(dep: ExternalDependency) = isBomDep(dep.getGroup, dep.getName)
    def configurations(names: Seq[String]) = getConfigurations.iterator.asScala
      .filter(config => names.contains(config.getName))
    def mvnDeps(configs: String*) = configurations(configs)
      .flatMap(config =>
        config.getDependencies.iterator.asScala.collect {
          case dep: ExternalDependency if !isBom(dep) => toMvnDep(dep, config)
        }
      )
      .toSeq
    def bomMvnDeps(configs: String*) = configurations(configs)
      .flatMap(config =>
        config.getDependencies.iterator.asScala.collect {
          case dep: ExternalDependency if isBom(dep) => toMvnDep(dep, config)
        }
      )
      .toSeq
    def moduleDeps(configs: String*) = configurations(configs)
      .flatMap(_.getDependencies.iterator.asScala.collect {
        case dep: ProjectDependency => toModuleDep(dep)
      })
      .filter(_.segments != segments)
      .toSeq
    def javacOptionsForTask(compileTaskName: String) = getTasks.findByName(compileTaskName) match {
      case task: JavaCompile =>
        ctx.releaseVersion(task.getOptions).fold(
          // When not configured explicitly, "-source" and "-target" default to the
          // `languageVersion` of the Java toolchain.
          Option(task.getSourceCompatibility).fold(Nil)(Seq("-source", _)) ++
            Option(task.getTargetCompatibility).fold(Nil)(Seq("-target", _))
        )(n => Seq("--release", n.toString)) ++
          Option(task.getOptions.getEncoding).fold(Nil)(Seq("-encoding", _)) ++
          task.getOptions.getAllCompilerArgs.iterator.asScala.map(_.trim).toSeq
      case _ => Nil
    }

    val coursierModuleConfig = {
      val toUrlString: PartialFunction[ArtifactRepository, String] = {
        case repo: UrlArtifactRepository => repo.getUrl.toURL.toExternalForm
      }
      val skip = Seq(
        getRepositories.mavenCentral(),
        getRepositories.mavenLocal(),
        getRepositories.gradlePluginPortal()
      ).collect(toUrlString)
      getRepositories.iterator.asScala.collect(toUrlString).distinct.toSeq.diff(skip) match {
        case Nil => None
        case repositories => Some(CoursierModuleConfig(repositories = repositories))
      }
    }
    val errorProneDeps = mvnDeps("errorprone")
    val (errorProneModuleConfig, javacOptions) = findErrorProneModuleConfigJavacOptions(
      javacOptionsForTask("compileJava"),
      errorProneDeps
    )
    val javaPluginExtension = Option(getExtensions.findByType(classOf[JavaPluginExtension]))
    val javaHomeModuleConfig = javaPluginExtension.flatMap(ext =>
      findJavaHomeModuleConfig(ctx.javaVersion(ext), javacOptions)
    )
    val mavenPublication = Option(getExtensions.findByType(classOf[PublishingExtension]))
      .flatMap(ext => ext.getPublications.withType(classOf[MavenPublication]).asScala.headOption)
    val javaModuleConfig = Option.when(getPluginManager.hasPlugin("java")) {
      JavaModuleConfig(
        mvnDeps = mvnDeps("implementation", "api"),
        compileMvnDeps = mvnDeps("compileOnly", "compileOnlyApi"),
        runMvnDeps = mvnDeps("runtimeOnly"),
        bomMvnDeps = bomMvnDeps("implementation", "api"),
        moduleDeps = moduleDeps("implementation", "api"),
        compileModuleDeps = moduleDeps("compileOnly", "compileOnlyApi"),
        runModuleDeps = moduleDeps("runtimeOnly"),
        javacOptions = javacOptions,
        artifactName =
          mavenPublication.fold(null)(pub => overrideArtifactName(pub.getArtifactId, segments))
      )
    }
    val publishModuleConfig = mavenPublication.map { pub =>
      PublishModuleConfig(
        pomPackagingType = overridePomPackagingType(pub.getPom.getPackaging),
        pomSettings = toPomSettings(pub.getPom, pub.getGroupId),
        publishVersion = getVersion.toString
      )
    }
    val configs = Seq(
      javaModuleConfig,
      publishModuleConfig,
      errorProneModuleConfig,
      javaHomeModuleConfig,
      coursierModuleConfig
    ).flatten

    val testModule = if (os.exists(moduleDir / "src/test")) {
      val mvnDeps0 = mvnDeps("testImplementation")
      findTestModuleMixin(mvnDeps0).map { mixin =>
        val (testErrorProneModuleConfig, testJavacOptions) = findErrorProneModuleConfigJavacOptions(
          javacOptionsForTask("compileTestJava"),
          errorProneDeps
        )
        val testJavaModuleConfig = JavaModuleConfig(
          mvnDeps = mvnDeps0,
          compileMvnDeps = mvnDeps("testCompileOnly"),
          runMvnDeps = mvnDeps("testRuntimeOnly"),
          bomMvnDeps = bomMvnDeps("testImplementation"),
          moduleDeps = moduleDeps("testImplementation"),
          compileModuleDeps = moduleDeps("testCompileOnly"),
          runModuleDeps = moduleDeps("testRuntimeOnly"),
          javacOptions = ModuleConfig.inheritedOptions(testJavacOptions, javacOptions)
        )
        val testSupertypes = "MavenTests" +:
          (if (errorProneModuleConfig.isEmpty) Nil else Seq("ErrorProneModule"))
        val testConfigs = testJavaModuleConfig +: errorProneModuleConfig.toSeq
        TestModuleRepr(
          supertypes = testSupertypes,
          mixins = Seq(mixin),
          configs = testConfigs,
          testParallelism = false,
          testSandboxWorkingDir = false
        )
      }
    } else None

    if (configs.isEmpty && testModule.isEmpty) ModuleRepr(segments)
    else {
      val supertypes = Seq("MavenModule") ++
        (if (publishModuleConfig.isEmpty) Nil else Seq("PublishModule")) ++
        (if (errorProneModuleConfig.isEmpty) Nil else Seq("ErrorProneModule"))
      val configs0 = if (testModule.nonEmpty && javaModuleConfig.isEmpty)
        JavaModuleConfig() +: configs
      else configs
      ModuleRepr(
        segments = segments,
        supertypes = supertypes,
        configs = configs0,
        testModule = testModule
      )
    }
  }

  def toMvnDep(dep: ExternalDependency, config: Configuration) = {
    import dep.*
    val artifact = getArtifacts.asScala.headOption
    ModuleConfig.MvnDep(
      organization = getGroup,
      name = getName,
      version = Option(getVersion)
        .orElse(config.getAllDependencyConstraints.iterator.asScala.collectFirst {
          case dc if dc.getGroup == getGroup || dc.getName == getName => dc.getVersion
        }),
      classifier = artifact.flatMap(a => Option(a.getClassifier)),
      `type` = artifact.flatMap(a => Option(a.getType)),
      excludes = getExcludeRules.asScala.iterator.map(rule => rule.getGroup -> rule.getModule).toSeq
    )
  }

  def toModuleDep(dep: ProjectDependency) =
    ModuleConfig.ModuleDep(
      os.Path(ctx.project(dep).getProjectDir).subRelativeTo(workspace).segments
    )

  def toPomSettings(pom: MavenPom, groupId: String) = {
    import pom.*
    val (licenses, versionControl, developers) = pom match {
      case pom: DefaultMavenPom =>
        (
          pom.getLicenses.iterator.asScala.map(toLicense).toSeq,
          toVersionControl(pom.getScm),
          pom.getDevelopers.iterator.asScala.map(toDeveloper).toSeq
        )
      case _ => (Nil, ModuleConfig.VersionControl(), Nil)
    }
    ModuleConfig.PomSettings(
      description = getDescription.getOrNull,
      organization = groupId,
      url = getUrl.getOrNull,
      licenses = licenses,
      versionControl = versionControl,
      developers = developers
    )
  }

  def toLicense(license: MavenPomLicense) = {
    import license.*
    ModuleConfig.License(
      name = getName.getOrNull,
      url = getUrl.getOrNull,
      distribution = getDistribution.getOrNull
    )
  }

  def toVersionControl(scm: MavenPomScm) = {
    if (null == scm) ModuleConfig.VersionControl()
    else
      import scm.*
      ModuleConfig.VersionControl(
        browsableRepository = Option(getUrl.getOrNull),
        connection = Option(getConnection.getOrNull),
        developerConnection = Option(getDeveloperConnection.getOrNull),
        tag = Option(getTag.getOrNull)
      )
  }

  def toDeveloper(developer: MavenPomDeveloper) = {
    import developer.*
    ModuleConfig.Developer(
      id = getId.getOrNull,
      name = getName.getOrNull,
      url = getUrl.getOrNull,
      organization = Option(getOrganization.getOrNull),
      organizationUrl = Option(getOrganizationUrl.getOrNull)
    )
  }
}
