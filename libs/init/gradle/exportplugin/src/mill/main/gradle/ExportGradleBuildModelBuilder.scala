package mill.main.gradle

import mill.main.buildgen.*
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.{ArtifactRepository, UrlArtifactRepository}
import org.gradle.api.artifacts.{Dependency, ExternalDependency, ProjectDependency}
import org.gradle.api.plugins.JavaPlugin.*
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.*
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.tooling.provider.model.{ToolingModelBuilder, ToolingModelBuilderRegistry}

import java.io.File
import javax.inject.Inject
import scala.jdk.CollectionConverters.*

class ExportGradleBuildModelBuilder(
    ctx: GradleBuildContext,
    testModuleName: String,
    workspace: os.Path
) extends ToolingModelBuilder {

  def canBuild(modelName: String) = classOf[ExportGradleBuildModel].getName == modelName

  def buildAll(modelName: String, project: Project) = {
    val packages = Iterator.iterate(Set(project))(_.flatMap(_.getSubprojects.asScala))
      .takeWhile(_.nonEmpty)
      .flatten
      .map(toModule)
      .toSeq
    new ExportGradleBuildModel.Impl(upickle.default.write(packages))
  }

  def toModule(project0: Project) = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    val segments = moduleDir.subRelativeTo(workspace).segments

    def moduleDeps(configNames: String*) = getConfigurations.iterator.asScala.flatMap: config =>
      if (configNames.contains(config.getName)) config.getDependencies.iterator.asScala.collect:
        case dep: ProjectDependency => toModuleDep(dep)
      else Nil
    .toSeq
    def mvnDeps(configNames: String*) = getConfigurations.iterator.asScala.flatMap: config =>
      if (configNames.contains(config.getName)) config.getDependencies.iterator.asScala.collect:
        case dep: ExternalDependency => toMvnDep(dep)
      .filterNot(JavaModuleConfig.isBomMvnDep)
      else Nil
    .toSeq
    def bomMvnDeps(configNames: String*) = getConfigurations.iterator.asScala.flatMap: config =>
      if (configNames.contains(config.getName)) config.getDependencies.iterator.asScala.collect:
        case dep: ExternalDependency => toMvnDep(dep)
      .filter(JavaModuleConfig.isBomMvnDep)
      else Nil
    .toSeq

    val testModule =
      val testDeps = mvnDeps(TEST_IMPLEMENTATION_CONFIGURATION_NAME)
      val testCompileDeps = mvnDeps(TEST_COMPILE_ONLY_CONFIGURATION_NAME)
      val testRunDeps = mvnDeps(TEST_RUNTIME_ONLY_CONFIGURATION_NAME)
      if (os.exists(moduleDir / "src/test")) TestModuleRepr.mixinAndMandatoryMvnDeps(
        testDeps ++ testCompileDeps ++ testRunDeps
      ).map: (mixin, mandatoryMvnDeps) =>
        TestModuleRepr(
          name = testModuleName,
          supertypes = Seq("MavenTests"),
          mixins = Seq(mixin),
          configs = Seq(JavaModuleConfig(
            mandatoryMvnDeps = mandatoryMvnDeps,
            mvnDeps = testDeps.diff(mandatoryMvnDeps),
            compileMvnDeps = testCompileDeps.diff(mandatoryMvnDeps),
            runMvnDeps = testRunDeps.diff(mandatoryMvnDeps),
            bomMvnDeps = bomMvnDeps(TEST_IMPLEMENTATION_CONFIGURATION_NAME),
            moduleDeps = moduleDeps(TEST_IMPLEMENTATION_CONFIGURATION_NAME),
            compileModuleDeps = moduleDeps(TEST_COMPILE_ONLY_CONFIGURATION_NAME),
            runModuleDeps = moduleDeps(TEST_RUNTIME_ONLY_CONFIGURATION_NAME)
          ))
        )
      else None

    val javaCompileTask = Option(getTasks.findByName(COMPILE_JAVA_TASK_NAME)).collect:
      case task: JavaCompile => task
    val (javacOptions, errorProneModuleConfig) = ErrorProneModuleConfig.javacOptionsAndConfig(
      javaCompileTask.fold(Nil) { task =>
        // javac requires --release to be mutually exclusive with -source/-target
        val opts = Option(task.getOptions.getRelease.getOrNull).fold(
          Option(task.getSourceCompatibility).fold(Nil)(Seq("-source", _)) ++
            Option(task.getTargetCompatibility).fold(Nil)(Seq("-target", _))
        )(n => Seq("--release", n.toString)) ++
          Option(task.getOptions.getEncoding).fold(Nil)(Seq("-encoding", _)) ++
          task.getOptions.getAllCompilerArgs.asScala.toSeq
        opts.diff(JavaModuleConfig.unsupportedJavacOptions)
      },
      mvnDeps("errorprone")
    )
    val javaModuleConfig = Option.when(getPluginManager.hasPlugin("java")):
      JavaModuleConfig(
        mvnDeps = mvnDeps(IMPLEMENTATION_CONFIGURATION_NAME, API_CONFIGURATION_NAME),
        compileMvnDeps =
          mvnDeps(COMPILE_ONLY_CONFIGURATION_NAME, COMPILE_ONLY_API_CONFIGURATION_NAME),
        runMvnDeps = mvnDeps(RUNTIME_ONLY_CONFIGURATION_NAME),
        bomMvnDeps = bomMvnDeps(IMPLEMENTATION_CONFIGURATION_NAME, API_CONFIGURATION_NAME),
        moduleDeps = moduleDeps(IMPLEMENTATION_CONFIGURATION_NAME, API_CONFIGURATION_NAME),
        compileModuleDeps =
          moduleDeps(COMPILE_ONLY_CONFIGURATION_NAME, COMPILE_ONLY_API_CONFIGURATION_NAME),
        runModuleDeps = moduleDeps(RUNTIME_ONLY_CONFIGURATION_NAME),
        javacOptions = javacOptions
      )
    val javaHomeModuleConfig = ctx.jvmId(project0).map(JavaHomeModuleConfig(_))
    val coursierModuleConfig = {
      val repos = getRepositories
      val url: PartialFunction[ArtifactRepository, String] = {
        case repo: UrlArtifactRepository => repo.getUrl.toURL.toExternalForm
      }
      val skip = Seq(
        repos.mavenCentral(),
        repos.mavenLocal(),
        repos.gradlePluginPortal()
      ).collect(url)
      repos.iterator.asScala.collect(url).distinct.toSeq.diff(skip) match
        case Nil => None
        case repositories => Some(CoursierModuleConfig(repositories = repositories))
    }
    val publishModuleConfig = Option(getExtensions.findByType(classOf[PublishingExtension]))
      .flatMap: ext =>
        ext.getPublications.withType(classOf[MavenPublication]).asScala.headOption
      .map: pub =>
        PublishModuleConfig(
          pomPackagingType = toPomPackagingType(pub.getPom.getPackaging),
          pomSettings = toPomSettings(pub.getPom),
          artifactMetadata = toArtifactMetadata(pub),
          publishVersion = getVersion.toString
        )

    val configs = Seq(
      javaModuleConfig,
      javaHomeModuleConfig,
      coursierModuleConfig,
      publishModuleConfig,
      errorProneModuleConfig
    ).flatten
    if (configs.isEmpty && testModule.isEmpty) ModuleRepr(segments)
    else ModuleRepr(
      segments = segments,
      supertypes = Seq("MavenModule") ++
        (if (publishModuleConfig.isEmpty) Nil else Seq("PublishModule")) ++
        (if (errorProneModuleConfig.isEmpty) Nil else Seq("ErrorProneModule")),
      configs =
        if (testModule.nonEmpty && javaModuleConfig.isEmpty) JavaModuleConfig() +: configs
        else configs,
      testModule = testModule
    )
  }

  def toModuleDep(dep: ProjectDependency) = JavaModuleConfig.ModuleDep(
    os.Path(ctx.project(dep).getProjectDir).subRelativeTo(workspace).segments
  )

  def toMvnDep(dep: ExternalDependency) = {
    import dep.*
    val artifact = getArtifacts.asScala.headOption
    JavaModuleConfig.mvnDep(
      org = getGroup,
      name = getName,
      version = getVersion,
      classifier = artifact.map(_.getClassifier),
      typ = artifact.map(_.getType),
      excludes = getExcludeRules.asScala.map(rule => rule.getGroup -> rule.getModule)
    )
  }

  def toPomPackagingType(tpe: String) = {
    if ("jar" == tpe) null else tpe
  }

  def toPomSettings(pom: MavenPom) = {
    import pom.*
    var org: String = null
    organization(pomOrg => if (null != pomOrg) org = pomOrg.getName.getOrNull)
    val licenses = Seq.newBuilder[PublishModuleConfig.License]
    pom.licenses(_.license(licenses += toLicense(_)))
    var versionControl: PublishModuleConfig.VersionControl = null
    pom.scm(scm => versionControl = toVersionControl(scm))
    val developers = Seq.newBuilder[PublishModuleConfig.Developer]
    pom.developers(_.developer(developers += toDeveloper(_)))
    PublishModuleConfig.PomSettings(
      description = getDescription.getOrNull,
      organization = org,
      url = getUrl.getOrNull,
      licenses = licenses.result(),
      versionControl = versionControl,
      developers = developers.result()
    )
  }

  def toLicense(license: MavenPomLicense) = {
    import license.*
    PublishModuleConfig.License(
      id = getName.getOrNull,
      name = getName.getOrNull,
      url = getUrl.getOrNull,
      distribution = getDistribution.getOrNull
    )
  }

  def toVersionControl(scm: MavenPomScm) = {
    if (null == scm) PublishModuleConfig.VersionControl()
    else
      import scm.*
      PublishModuleConfig.VersionControl(
        browsableRepository = Option(getUrl.getOrNull),
        connection = Option(getConnection.getOrNull),
        developerConnection = Option(getDeveloperConnection.getOrNull),
        tag = Option(getTag.getOrNull)
      )
  }

  def toDeveloper(developer: MavenPomDeveloper) = {
    import developer.*
    PublishModuleConfig.Developer(
      id = getId.getOrNull,
      name = getName.getOrNull,
      url = getUrl.getOrNull,
      organization = Option(getOrganization.getOrNull),
      organizationUrl = Option(getOrganizationUrl.getOrNull)
    )
  }

  def toArtifactMetadata(pub: MavenPublication) = {
    import pub.*
    PublishModuleConfig.Artifact(
      group = getGroupId,
      id = getArtifactId,
      version = getVersion
    )
  }
}
