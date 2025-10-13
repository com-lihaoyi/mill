package mill.main.gradle

import mill.main.buildgen.*
import mill.main.buildgen.ModuleConfig.*
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.repositories.{ArtifactRepository, UrlArtifactRepository}
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.*
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.{Project, Task}
import org.gradle.tooling.provider.model.{ToolingModelBuilder, ToolingModelBuilderRegistry}

import java.io.File
import javax.inject.Inject
import scala.jdk.CollectionConverters.*
import scala.reflect.TypeTest

class BuildModelBuilder(ctx: GradleBuildCtx, workspace: os.Path) extends ToolingModelBuilder {

  def canBuild(modelName: String) = classOf[BuildModel].getName == modelName

  def buildAll(modelName: String, project: Project) = {
    val exportedBuild = Iterator.iterate(Set(project))(_.flatMap(_.getSubprojects.asScala))
      .takeWhile(_.nonEmpty)
      .flatten
      .map(toPackage)
      .toSeq
    new BuildModel.Impl(upickle.default.write(exportedBuild))
  }

  def toPackage(project0: Project) = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    val segments = moduleDir.subRelativeTo(workspace).segments

    def isBom(dep: ExternalDependency) = isBomDep(dep.getGroup, dep.getName)
    def configurations(names: Seq[String]) = getConfigurations.asScala
      .filter(config => names.contains(config.getName))
    def mvnDeps(configs: String*) = configurations(configs)
      .flatMap(config =>
        config.getDependencies.asScala.collect {
          case dep: ExternalDependency if !isBom(dep) => toMvnDep(dep, config)
        }
      )
      .toSeq
    def bomMvnDeps(configs: String*) = configurations(configs)
      .flatMap(config =>
        config.getDependencies.asScala.collect {
          case dep: ExternalDependency if isBom(dep) => toMvnDep(dep, config)
        }
      )
      .toSeq
    def moduleDeps(configs: String*) = configurations(configs)
      .flatMap(_.getDependencies.asScala.collect {
        case dep: ProjectDependency => toModuleDep(dep)
      })
      .filter(_.segments != segments)
      .toSeq
    def task[T](name: String)(using T: TypeTest[Task, T]) = getTasks.findByName(name) match {
      case T(t) => Some(t)
      case _ => None
    }
    def javacOptionsFromTask(task: JavaCompile) = {
      val compatOptions = ctx.releaseVersion(task.getOptions).fold(
        // When not configured explicitly, "-source" and "-target" default to the
        // `languageVersion` of the Java toolchain.
        Option(task.getSourceCompatibility).fold(Nil)(Seq("-source", _)) ++
          Option(task.getTargetCompatibility).fold(Nil)(Seq("-target", _))
      )(n => Seq("--release", n.toString))
      compatOptions ++
        Option(task.getOptions.getEncoding).fold(Nil)(Seq("-encoding", _)) ++
        task.getOptions.getAllCompilerArgs.asScala.toSeq
    }

    val skipRepositories = Seq(
      getRepositories.mavenCentral(),
      getRepositories.mavenLocal(),
      getRepositories.gradlePluginPortal()
    ).collect(toRepositoryUrlString)
    val mainCoursierModule = getRepositories.asScala.collect(toRepositoryUrlString)
      .distinct.toSeq.diff(skipRepositories) match {
      case Nil => None
      case repositories => Some(CoursierModule(repositories = repositories))
    }
    val errorProneDeps = mvnDeps("errorprone")
    val (mainErrorProneModule, mainJavacOptions) = ErrorProneModule.find(
      task[JavaCompile]("compileJava").fold(Nil)(javacOptionsFromTask),
      errorProneDeps
    )
    val javaPluginExtension = Option(getExtensions.findByType(classOf[JavaPluginExtension]))
    val mainJavaHomeModule =
      javaPluginExtension.flatMap(ext =>
        JavaHomeModule.find(ctx.javaVersion(ext), mainJavacOptions)
      )
    val mavenPublication = Option(getExtensions.findByType(classOf[PublishingExtension]))
      .flatMap(ext => ext.getPublications.withType(classOf[MavenPublication]).asScala.headOption)
    val mainJavaModule = Option.when(getPluginManager.hasPlugin("java")) {
      JavaModule(
        mvnDeps = mvnDeps("implementation", "api"),
        compileMvnDeps = mvnDeps("compileOnly", "compileOnlyApi"),
        runMvnDeps = mvnDeps("runtimeOnly"),
        bomMvnDeps = bomMvnDeps("implementation", "api"),
        moduleDeps = moduleDeps("implementation", "api"),
        compileModuleDeps = moduleDeps("compileOnly", "compileOnlyApi"),
        runModuleDeps = moduleDeps("runtimeOnly"),
        javacOptions = mainJavacOptions,
        artifactName = mavenPublication.fold(null)(_.getArtifactId)
      )
    }
    val mainPublishModule = mavenPublication.map { pub =>
      PublishModule(
        pomPackagingType = PublishModule.pomPackagingTypeOverride(pub.getPom.getPackaging),
        pomSettings = toPomSettings(pub.getPom, pub.getGroupId),
        publishVersion = getVersion.toString
      )
    }

    val testModule = if (os.exists(moduleDir / "src/test")) {
      TestModule.mixin(mvnDeps("testImplementation")).map { testModuleMixin =>
        val (testErrorProneModule, testJavacOptions) = ErrorProneModule.find(
          task[JavaCompile]("compileTestJava").fold(Nil)(javacOptionsFromTask),
          errorProneDeps
        )
        val testJavaModule = JavaModule(
          mvnDeps = mvnDeps("testImplementation"),
          compileMvnDeps = mvnDeps("testCompileOnly"),
          runMvnDeps = mvnDeps("testRuntimeOnly"),
          bomMvnDeps = bomMvnDeps("testImplementation"),
          moduleDeps = moduleDeps("testImplementation"),
          compileModuleDeps = moduleDeps("testCompileOnly"),
          runModuleDeps = moduleDeps("testRuntimeOnly"),
          javacOptions = inheritedOptions(testJavacOptions, mainJavacOptions)
        )
        val testModule = TestModule(
          testParallelism = "false",
          testSandboxWorkingDir = "false"
        )
        val testConfigs = Seq(testJavaModule) ++ testErrorProneModule ++ Seq(testModule)
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
    } else None

    val mainConfigs = Seq(
      mainJavaModule,
      mainErrorProneModule,
      mainJavaHomeModule,
      mainPublishModule,
      mainCoursierModule
    ).flatten
    val mainModule = if (mainConfigs.isEmpty && testModule.isEmpty) ModuleSpec(moduleDir.last)
    else {
      val mainSupertypes = "MavenModule" +: mainConfigs.collect {
        case _: PublishModule => "PublishModule"
        case _: ErrorProneModule => "ErrorProneModule"
      }
      ModuleSpec(
        name = moduleDir.last,
        supertypes = mainSupertypes,
        configs = mainConfigs,
        nestedModules = testModule.toSeq
      )
    }

    PackageSpec(segments, mainModule)
  }

  val toRepositoryUrlString: PartialFunction[ArtifactRepository, String] = {
    case repo: UrlArtifactRepository => repo.getUrl.toURL.toExternalForm
  }

  def toMvnDep(dep: ExternalDependency, config: Configuration) = {
    import dep.*
    val artifact = getArtifacts.asScala.headOption
    MvnDep(
      organization = getGroup,
      name = getName,
      version = Option(getVersion).orElse {
        config.getAllDependencyConstraints.asScala.collectFirst {
          case dc if dc.getGroup == getGroup || dc.getName == getName => dc.getVersion
        }
      },
      classifier = artifact.flatMap(a => Option(a.getClassifier)),
      `type` = artifact.flatMap(a => Option(a.getType)),
      excludes = getExcludeRules.asScala.map(rule => rule.getGroup -> rule.getModule).toSeq
    )
  }

  def toModuleDep(dep: ProjectDependency) =
    ModuleDep(
      os.Path(ctx.project(dep).getProjectDir).subRelativeTo(workspace).segments
    )

  def toPomSettings(pom: MavenPom, groupId: String) = {
    import pom.*
    val (licenses, versionControl, developers) = pom match {
      case pom: DefaultMavenPom =>
        (
          pom.getLicenses.asScala.map(toLicense).toSeq,
          toVersionControl(pom.getScm),
          pom.getDevelopers.asScala.map(toDeveloper).toSeq
        )
      case _ => (Nil, VersionControl(), Nil)
    }
    PomSettings(
      description = Option(getDescription.getOrNull),
      organization = Option(groupId),
      url = Option(getUrl.getOrNull),
      licenses = licenses,
      versionControl = versionControl,
      developers = developers
    )
  }

  def toLicense(license: MavenPomLicense) = {
    import license.*
    License(
      name = getName.getOrNull,
      url = getUrl.getOrNull,
      distribution = getDistribution.getOrNull
    )
  }

  def toVersionControl(scm: MavenPomScm) = {
    if (null == scm) VersionControl()
    else
      import scm.*
      VersionControl(
        browsableRepository = Option(getUrl.getOrNull),
        connection = Option(getConnection.getOrNull),
        developerConnection = Option(getDeveloperConnection.getOrNull),
        tag = Option(getTag.getOrNull)
      )
  }

  def toDeveloper(developer: MavenPomDeveloper) = {
    import developer.*
    Developer(
      id = getId.getOrNull,
      name = getName.getOrNull,
      url = getUrl.getOrNull,
      organization = Option(getOrganization.getOrNull),
      organizationUrl = Option(getOrganizationUrl.getOrNull)
    )
  }
}
