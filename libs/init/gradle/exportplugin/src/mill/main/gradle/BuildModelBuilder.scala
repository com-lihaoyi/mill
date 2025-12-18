package mill.main.gradle

import mill.main.buildgen.*
import mill.main.buildgen.ModuleSpec.*
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.UrlArtifactRepository
import org.gradle.api.attributes.Category
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependencyConstraint
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.*
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.tooling.provider.model.ToolingModelBuilder

import scala.jdk.CollectionConverters.*
import scala.reflect.TypeTest
import scala.util.Try

class BuildModelBuilder(ctx: GradleBuildCtx, objectFactory: ObjectFactory, workspace: os.Path)
    extends ToolingModelBuilder {

  def canBuild(modelName: String) = classOf[BuildModel].getName == modelName

  def buildAll(modelName: String, project: Project) = {
    val exportedBuild = Iterator.iterate(Set(project))(_.flatMap(_.getSubprojects.asScala))
      .takeWhile(_.nonEmpty)
      .flatten
      .map(toPackage)
      .toSeq
    new BuildModel.Impl(upickle.default.write(exportedBuild))
  }

  private def toPackage(project0: Project): PackageSpec = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    var mainModule = ModuleSpec(
      name = moduleDir.last,
      repositories = getRepositories.asScala.toSeq.collect(toRepositoryUrlString).distinct
        .diff(Seq(
          getRepositories.mavenCentral,
          getRepositories.mavenLocal,
          getRepositories.gradlePluginPortal
        ).collect(toRepositoryUrlString))
    )

    if (getPluginManager.hasPlugin("java-platform")) {
      val configs = getConfigurations.asScala.toSeq
      val deps = configs.flatMap(_.getDependencies.asScala)
      val constraints = configs.flatMap(_.getDependencyConstraints.asScala)
      mainModule = mainModule.copy(
        imports = "import mill.javalib.*" +: mainModule.imports,
        supertypes = "JavaModule" +: "BomModule" +: mainModule.supertypes,
        bomMvnDeps = deps.filter(isBom).collect(toMvnDep),
        depManagement = constraints.collect(toMvnDep),
        moduleDeps = constraints.collect(toModuleDep),
        bomModuleDeps = deps.filter(isBom).collect(toModuleDep)
      )
    } else if (getPluginManager.hasPlugin("java")) {
      val configs = getConfigurations.asScala.toSeq
      def deps(configNames: String*) = configs
        .filter(config => configNames.contains(config.getName))
        .flatMap(_.getDependencies.asScala)
      def mvnDeps(configNames: String*) = deps(configNames*).filterNot(isBom).collect(toMvnDep)
      def moduleDeps(configNames: String*) =
        deps(configNames*).filterNot(isBom).collect(toModuleDep)
      val mainConfigs = configs.filter(config =>
        config.getName != "errorprone" && !config.getName.startsWith("test")
      )
      val mainDeps = mainConfigs.flatMap(_.getDependencies.asScala)
      val mainConstraints = mainConfigs.flatMap(_.getDependencyConstraints.asScala)
      def task[T](name: String)(using T: TypeTest[Task, T]) = getTasks.findByName(name) match {
        case T(t) => Some(t)
        case _ => None
      }
      val buildDir = os.Path(getLayout.getBuildDirectory.get().getAsFile)
      def sources(sets: Seq[SourceSet]) = {
        val toRelModule: PartialFunction[os.Path, os.RelPath] = {
          case path if !path.startsWith(buildDir) => path.relativeTo(moduleDir)
        }
        val sourcesFolders = Seq.newBuilder[os.SubPath]
        val sources = Seq.newBuilder[os.RelPath]
        val resources = Seq.newBuilder[os.RelPath]
        for (set <- sets) do {
          val (_sourcesFolders, _sources) = set.getJava.getSrcDirs.asScala.map(os.Path(_)).collect(
            toRelModule
          ).partition(_.ups == 0)
          sourcesFolders ++= _sourcesFolders.map(_.asSubPath)
          sources ++= _sources
          val res = set.getResources
          val _resources = if (res.getIncludes.isEmpty && res.getExcludes.isEmpty)
            res.getSrcDirs.asScala.toSeq.map(os.Path(_)).collect(toRelModule)
          else res.getFiles.asScala.toSeq.map(os.Path(_)).collect(toRelModule)
          resources ++= _resources
        }
        (sourcesFolders.result(), sources.result(), resources.result())
      }
      val (testSourceSets, mainSourceSets) =
        Option(getExtensions.findByType(classOf[JavaPluginExtension]))
          .flatMap(ctx.sourceSetContainer).toSeq.flatMap(_.asScala.toSeq)
          .partition(isTest)
      val (mainSourcesFolders, mainSources, mainResources) = sources(mainSourceSets)
      val errorProneDeps = mvnDeps("errorprone")
      mainModule = mainModule.copy(
        imports = "import mill.javalib.*" +: mainModule.imports,
        supertypes = "MavenModule" +: mainModule.supertypes,
        mvnDeps = mvnDeps("implementation", "api"),
        compileMvnDeps = mvnDeps("compileOnly", "compileOnlyApi"),
        runMvnDeps = mvnDeps("runtimeOnly"),
        bomMvnDeps = mainDeps.filter(isBom).collect(toMvnDep),
        depManagement = mainConstraints.collect(toMvnDep),
        javacOptions = task[JavaCompile]("compileJava").fold(Nil)(javacOptions),
        moduleDeps = moduleDeps("implementation", "api"),
        compileModuleDeps = moduleDeps("compileOnly", "compileOnlyApi"),
        runModuleDeps = moduleDeps("runtimeOnly"),
        bomModuleDeps = mainDeps.filter(isBom).collect(toModuleDep),
        sourcesFolders = if (mainSourcesFolders == Seq(os.sub / "src/main/java")) Nil
        else mainSourcesFolders,
        sources = Values(mainSources, appendSuper = true),
        resources = if (mainResources == Seq(os.rel / "src/main/resources")) Nil else mainResources
      ).withErrorProneModule(errorProneDeps)

      for {
        testRunConfig <- configs.find(_.getName == "testRuntimeClasspath")
        testRunDeps = testRunConfig.getAllDependencies.asScala.toSeq
        testMixin <- ModuleSpec.testModuleMixin(testRunDeps.collect(toMvnDep))
        if os.exists(moduleDir / "src/test")
      } do {
        val testConfigs = configs.filter(config => config.getName.startsWith("test"))
        val testConstraints = testConfigs.flatMap(_.getDependencyConstraints.asScala)
        val (testSourcesFolders, testSources, testResources) = sources(testSourceSets)
        var testModule = ModuleSpec(
          name = "test",
          supertypes = Seq("MavenTests"),
          mixins = Seq(testMixin),
          forkArgs = task[Test]("test").fold(Nil) { task =>
            task.getSystemProperties.asScala.map {
              case (k, v) => Opt(s"-D$k=$v")
            }.toSeq ++ Opt.groups(task.getJvmArgs.asScala.toSeq)
          },
          forkWorkingDir = Some(os.rel),
          mvnDeps = mvnDeps("testImplementation"),
          compileMvnDeps = mvnDeps("testCompileOnly"),
          runMvnDeps = mvnDeps("testRuntimeOnly"),
          bomMvnDeps = testRunDeps.filter(isBom).collect(toMvnDep),
          depManagement = testConstraints.collect(toMvnDep),
          javacOptions = task[JavaCompile]("compileTestJava").fold(Nil)(javacOptions),
          moduleDeps = Values(
            moduleDeps("testImplementation")
              .diff(Seq(ModuleDep(moduleDir.subRelativeTo(workspace).segments))),
            appendSuper = true
          ),
          compileModuleDeps = moduleDeps("testCompileOnly"),
          runModuleDeps = moduleDeps("testRuntimeOnly"),
          bomModuleDeps = testRunDeps.filter(isBom).collect(toModuleDep),
          sourcesFolders = if (testSourcesFolders == Seq(os.sub / "src/test/java")) Nil
          else testSourcesFolders,
          sources = Values(testSources, appendSuper = true),
          resources =
            if (testResources == Seq(os.rel / "src/test/resources")) Nil else testResources,
          testParallelism = Some(false),
          testSandboxWorkingDir = Some(false)
        ).withErrorProneModule(errorProneDeps)
        if (testMixin == "TestModule.Junit5") {
          testModule.mvnDeps.base.collectFirst {
            case dep if dep.organization == "org.junit.jupiter" && dep.version.nonEmpty =>
              val junitVersion = dep.version
              testModule = testModule.withJupiterInterface(junitVersion)
              val launcherDep = testModule.runMvnDeps.base.find(
                _.is("org.junit.platform", "junit-platform-launcher")
              )
              if (launcherDep.forall(_.version.isEmpty)) {
                if (launcherDep.isEmpty) {
                  testModule = testModule.copy(runMvnDeps =
                    testModule.runMvnDeps.base :+
                      MvnDep("org.junit.platform", "junit-platform-launcher", "")
                  )
                }
                testModule = testModule.copy(bomMvnDeps =
                  testModule.bomMvnDeps.base.appended(
                    MvnDep("org.junit", "junit-bom", junitVersion)
                  ).distinct
                )
              }
          }
        }
        mainModule = mainModule.copy(test = Some(testModule))
      }
    }

    for {
      pubExt <- Option(getExtensions.findByType(classOf[PublishingExtension]))
      pub <- pubExt.getPublications.withType(classOf[MavenPublication]).asScala.headOption
      pom = Option(pub.getPom)
    } do {
      mainModule = mainModule.copy(
        imports = "import mill.javalib.*" +: "import mill.javalib.publish.*" +: mainModule.imports,
        supertypes = mainModule.supertypes :+ "PublishModule",
        artifactName = Option(pub.getArtifactId),
        pomPackagingType = pom.flatMap(toPomPackagingType),
        pomSettings = pom.map(toPomSettings(_, pub.getGroupId)),
        publishVersion = Option(getVersion).map(_.toString)
      )
    }

    PackageSpec(moduleDir.subRelativeTo(workspace), mainModule)
  }

  private val toRepositoryUrlString: PartialFunction[ArtifactRepository, String] = {
    case repo: UrlArtifactRepository => repo.getUrl.toURL.toExternalForm
  }

  private val platform = objectFactory.named(classOf[Category], Category.REGULAR_PLATFORM)
  private val enforcedPlatform = objectFactory.named(classOf[Category], Category.ENFORCED_PLATFORM)
  private def isBom(dep: Dependency | DependencyConstraint) = dep match {
    case dep: ModuleDependency =>
      val category = dep.getAttributes.getAttribute(Category.CATEGORY_ATTRIBUTE)
      category == platform || category == enforcedPlatform
    case dep: DependencyConstraint =>
      val category = dep.getAttributes.getAttribute(Category.CATEGORY_ATTRIBUTE)
      category == platform || category == enforcedPlatform
    case _ => false
  }

  private def toCoursierVersionConstraint(version: String) = version match {
    case null => ""
    case s"]${range}[" => s"($range)"
    case s"]${range}" => s"($range"
    case s"${range}[" => s"$range)"
    case s => s
  }
  private val toMvnDep: PartialFunction[Dependency | DependencyConstraint, MvnDep] = {
    case dep: ExternalDependency =>
      import dep.*
      val artifact = getArtifacts.asScala.headOption
      MvnDep(
        organization = getGroup,
        name = getName,
        version = toCoursierVersionConstraint(getVersion),
        classifier = artifact.flatMap(a => Option(a.getClassifier)),
        `type` = artifact.flatMap(_.getType match {
          case null | "jar" | "pom" => None
          case tpe => Some(tpe)
        }),
        excludes = getExcludeRules.asScala.map(rule => rule.getGroup -> rule.getModule).toSeq
      )
    case dep: DependencyConstraint if !dep.isInstanceOf[DefaultProjectDependencyConstraint] =>
      import dep.*
      MvnDep(
        organization = getGroup,
        name = getName,
        version = toCoursierVersionConstraint(getVersion)
      )
  }

  private val toModuleDep: PartialFunction[Dependency | DependencyConstraint, ModuleDep] = {
    case dep: ProjectDependency =>
      ModuleDep(os.Path(ctx.project(dep).getProjectDir).subRelativeTo(workspace).segments)
    case dep: DefaultProjectDependencyConstraint =>
      toModuleDep(dep.getProjectDependency)
  }

  private def javacOptions(task: JavaCompile) = {
    ctx.releaseVersion(task.getOptions).fold(Seq(
      Option(task.getSourceCompatibility).map(Opt("-source", _)),
      Option(task.getTargetCompatibility).map(Opt("-target", _))
    ).flatten)(n => Seq(Opt("--release", n.toString))) ++
      Option(task.getOptions.getEncoding).map(Opt("-encoding", _)) ++
      Opt.groups(task.getOptions.getAllCompilerArgs.asScala.toSeq)
  }

  private val TestSourceSetName = s"(?i)test".r.unanchored
  private def isTest(set: SourceSet) = TestSourceSetName.matches(set.getName)

  private def toPomPackagingType(pom: MavenPom): Option[String] =
    Try(pom.getPackaging).filter(_ != "jar").toOption

  private def toPomSettings(pom: MavenPom, groupId: String): PomSettings = {
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
      description = getDescription.getOrElse(""),
      organization = groupId,
      url = getUrl.getOrElse(""),
      licenses = licenses,
      versionControl = versionControl,
      developers = developers
    )
  }

  private def toLicense(license: MavenPomLicense): License = {
    import license.*
    License(
      name = getName.getOrElse(""),
      url = getUrl.getOrElse(""),
      distribution = getDistribution.getOrElse("")
    )
  }

  private def toVersionControl(scm: MavenPomScm): VersionControl = {
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

  private def toDeveloper(developer: MavenPomDeveloper): Developer = {
    import developer.*
    Developer(
      id = getId.getOrElse(""),
      name = getName.getOrElse(""),
      url = getUrl.getOrElse(""),
      organization = Option(getOrganization.getOrNull),
      organizationUrl = Option(getOrganizationUrl.getOrNull)
    )
  }
}
