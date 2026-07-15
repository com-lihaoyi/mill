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
    BuildModel.Impl(upickle.default.write(exportedBuild))
  }

  private def fixKotlinVersions(
    deps: Seq[MvnDep],
    kotlinVersionForDeps: String,
    kotlinTestResolvedNameOpt: Option[String]
  ): Seq[MvnDep] = {
    deps.map { dep =>
      if (dep.organization == "org.jetbrains.kotlin" && dep.name == "kotlin-test") {
        // org.jetbrains.kotlin:kotlin-test is a multiplatform POM that lacks JVM classes.
        // We map it to the actual platform variant (e.g. kotlin-test-junit5)
        // resolved by Gradle's test runtime classpath.
        val newName = kotlinTestResolvedNameOpt.getOrElse("kotlin-test")
        dep.copy(name = newName, version = kotlinVersionForDeps)
      } else if (dep.organization == "org.jetbrains.kotlin" && dep.version.isEmpty) {
        // Some Kotlin deps are declared without a version.
        // We pin them to the project's resolved Kotlin version.
        dep.copy(version = kotlinVersionForDeps)
      } else {
        dep
      }
    }
  }

  private case class ExtractedJvmData(
    configs: Seq[Configuration],
    kotlinVersionForDeps: String,
    kotlinTestResolvedNameOpt: Option[String],
    testMvnDepsList: Seq[MvnDep],
    testMixin: Seq[String],
    testBomDeps: Seq[Dependency],
    testConstraints: Seq[DependencyConstraint],
    testJavaCompile: Option[JavaCompile],
    mainJavaCompile: Option[JavaCompile],
    effectiveBomDeps: Seq[Dependency],
    mainConstraints: Seq[DependencyConstraint]
  )

  private def extractJvmData(project0: Project, isKotlin: Boolean, kotlinVersionOpt: Option[String]): ExtractedJvmData = {
    import project0.*
    val configs = getConfigurations.asScala.toSeq
    val kotlinVersionForDeps = kotlinVersionOpt.getOrElse("")
    val kotlinTestResolvedNameOpt = configs.find(_.getName == "testRuntimeClasspath")
      .flatMap { config =>
        Try(config.getResolvedConfiguration.getResolvedArtifacts.asScala).toOption
          .flatMap { artifacts =>
            artifacts.find(art => art.getModuleVersion.getId.getGroup == "org.jetbrains.kotlin" && art.getModuleVersion.getId.getName.startsWith("kotlin-test-"))
              .map(_.getModuleVersion.getId.getName)
          }
      }
    val testMvnDepsList = configs.find(_.getName == "testRuntimeClasspath")
      .fold(Nil)(_.getAllDependencies.asScala.toSeq.collect(toMvnDep))

    val testMixin = ModuleSpec.testModuleMixin {
      if (isKotlin && kotlinVersionForDeps.nonEmpty) {
        fixKotlinVersions(
          testMvnDepsList,
          kotlinVersionForDeps = kotlinVersionForDeps,
          kotlinTestResolvedNameOpt = kotlinTestResolvedNameOpt
        )
      } else testMvnDepsList
    }
    val (testConfigs, mainConfigs) = configs.partition(_.getName.startsWith("test"))
    val testBomDeps = testConfigs.flatMap(_.getDependencies.asScala).filter(isBom)
    val testConstraints = testConfigs.flatMap(_.getDependencyConstraints.asScala)

    def task[T](name: String)(using T: TypeTest[Task, T]) = getTasks.findByName(name) match {
      case T(t) => Some(t)
      case _ => None
    }

    val mainJavaCompile = task[JavaCompile]("compileJava")
    val testJavaCompile = task[JavaCompile]("compileTestJava")
    val mainBomDeps = mainConfigs.flatMap(_.getDependencies.asScala).filter(isBom)
    val mainConstraints = mainConfigs.flatMap(_.getDependencyConstraints.asScala)
    val isSpringBoot = isSpringBootProject(project0)
    val isQuarkus = isQuarkusProject(project0)

    // Exclude BOM deps that will be added by Mill Modules
    val effectiveBomDeps = {
      val exclusions = Set.newBuilder[(String, String)]
      if (isSpringBoot) exclusions += ("org.springframework.boot" -> "spring-boot-dependencies")
      if (isQuarkus) exclusions += ("io.quarkus.platform" -> "quarkus-bom")
      val exclusionSet = exclusions.result()

      mainBomDeps.filterNot(dep => exclusionSet.contains((dep.getGroup, dep.getName)))
    }

    ExtractedJvmData(
      configs = configs,
      kotlinVersionForDeps = kotlinVersionForDeps,
      kotlinTestResolvedNameOpt = kotlinTestResolvedNameOpt,
      testMvnDepsList = testMvnDepsList,
      testMixin = testMixin.toSeq,
      testBomDeps = testBomDeps,
      testConstraints = testConstraints,
      testJavaCompile = testJavaCompile,
      mainJavaCompile = mainJavaCompile,
      effectiveBomDeps = effectiveBomDeps,
      mainConstraints = mainConstraints
    )
  }

  private def configureJvmModule(
    project0: Project,
    data: ExtractedJvmData,
    mainModule0: ModuleSpec,
    isKotlin: Boolean,
    kotlinVersionOpt: Option[String]
  ): PackageSpec = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    val kotlinVersionForDeps = kotlinVersionOpt.getOrElse("")
    def fixKotlinVersions0(deps: Seq[MvnDep]): Seq[MvnDep] = {
      if (isKotlin && kotlinVersionForDeps.nonEmpty) {
        fixKotlinVersions(
          deps,
          kotlinVersionForDeps = kotlinVersionForDeps,
          kotlinTestResolvedNameOpt = data.kotlinTestResolvedNameOpt
        )
      } else deps
    }
    def deps(configNames: String*) = data.configs
      .filter(config => configNames.contains(config.getName))
      .flatMap(_.getDependencies.asScala)
    def mvnDeps(configNames: String*) = {
      fixKotlinVersions0(deps(configNames*).filterNot(isBom).collect(toMvnDep)).distinct
    }
    def moduleDeps(configNames: String*) =
      deps(configNames*).filterNot(isBom).collect(toModuleDep).distinct

    val isSpringBoot = isSpringBootProject(project0)
    val isQuarkus = isQuarkusProject(project0)

    var mainModule = mainModule0.copy(
      imports = (if (isKotlin) Seq("mill.kotlinlib.*", "mill.javalib.*") else Seq("mill.javalib.*")) ++ mainModule0.imports,
      supertypes = (if (isKotlin) "KotlinMavenModule" else "MavenModule") +: mainModule0.supertypes,
      kotlinVersion = kotlinVersionOpt,
      mvnDeps = mvnDeps("implementation", "api"),
      compileMvnDeps = mvnDeps("compileOnly", "compileOnlyApi"),
      runMvnDeps = mvnDeps("runtimeOnly"),
      bomMvnDeps = fixKotlinVersions0(data.effectiveBomDeps.collect(toMvnDep)),
      depManagement = fixKotlinVersions0(data.mainConstraints.collect(toMvnDep)),
      javacOptions = data.mainJavaCompile.fold(Nil)(javacOptions),
      moduleDeps = moduleDeps("implementation", "api"),
      compileModuleDeps = moduleDeps("compileOnly", "compileOnlyApi"),
      runModuleDeps = moduleDeps("runtimeOnly"),
      bomModuleDeps = data.configs.flatMap(_.getDependencies.asScala).filter(isBom).collect(toModuleDep),
      annotationProcessorsMvnDeps = mvnDeps("annotationProcessor")
    )

    val hasErrorPronePlugin = getPluginManager.hasPlugin("net.ltgt.errorprone")
    if (hasErrorPronePlugin) {
      mainModule = mainModule.withErrorProneModule(
        errorProneMvnDeps = mvnDeps("errorprone"),
        errorProneOptions = data.mainJavaCompile.fold(Nil)(errorProneOptions)
      )
    }
    if (isSpringBoot) {
      val pluginVersion = detectPluginVersion(project0, SpringBootPluginId)
      mainModule = mainModule.withSpringBootModule(pluginVersion)
    }

    if (isQuarkus) {
      val pluginVersion = detectPluginVersion(project0, QuarkusPluginId)
      mainModule =
        mainModule.withQuarkusModule(pluginVersion, Option(getGroup.toString).filter(_.nonEmpty))

      // Add PublishModule and artifact/pom settings.
      mainModule = mainModule.copy(
        imports = "mill.javalib.publish.*" +: mainModule.imports,
        supertypes = mainModule.supertypes :+ "PublishModule",
        artifactName = Option(getName),
        publishVersion = Option(getVersion).map(_.toString),
        pomSettings = Some(PomSettings(
          organization = getGroup.toString,
          description = Option(getDescription).getOrElse("")
        ))
      )
    }

    def task[T](name: String)(using T: TypeTest[Task, T]) = getTasks.findByName(name) match {
      case T(t) => Some(t)
      case _ => None
    }

    if (os.exists(moduleDir / "src/test")) {
      val testBomDeps = data.testBomDeps
      val testConstraints = data.testConstraints
      var testModule = ModuleSpec(
        name = "test",
        supertypes = (if (isKotlin) "KotlinMavenTests" else "MavenTests") +: data.testMixin,
        forkArgs = Values(
          task[Test]("test").fold(Nil) { task =>
            task.getSystemProperties.asScala.map {
              case (k, v) => Opt(s"-D$k=$v")
            }.toSeq ++ Opt.groups(task.getJvmArgs.asScala.toSeq)
          },
          appendSuper = true
        ),
        forkWorkingDir = Some("moduleDir"),
        mvnDeps = mvnDeps("testImplementation"),
        compileMvnDeps = mvnDeps("testCompileOnly"),
        runMvnDeps = mvnDeps("testRuntimeOnly"),
        bomMvnDeps = fixKotlinVersions0(testBomDeps.collect(toMvnDep)),
        depManagement = fixKotlinVersions0(testConstraints.collect(toMvnDep)),
        javacOptions = data.testJavaCompile.fold(Nil)(javacOptions),
        moduleDeps = Values(
          moduleDeps("testImplementation")
            .diff(Seq(ModuleDep(moduleDir.subRelativeTo(workspace).segments))),
          appendSuper = true
        ),
        compileModuleDeps = moduleDeps("testCompileOnly"),
        runModuleDeps = moduleDeps("testRuntimeOnly"),
        bomModuleDeps = testBomDeps.collect(toModuleDep),
        testParallelism = Some(false),
        testSandboxWorkingDir = Some(false),
        testFramework = Option.when(data.testMixin.isEmpty)(""),
        annotationProcessorsMvnDeps = mvnDeps("testAnnotationProcessor")
      )
      if (hasErrorPronePlugin) {
        testModule = testModule.withErrorProneModule(
          errorProneMvnDeps = mainModule.errorProneDeps,
          errorProneOptions = data.testJavaCompile.fold(Nil)(errorProneOptions)
        )
      }
      if (isSpringBoot) {
        testModule = testModule.withSpringBootTestsModule()
      }
      if (data.testMixin.contains("TestModule.Junit5")) {
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
      mainModule = mainModule.copy(children = Seq(testModule))
    }

    PackageSpec(moduleDir.subRelativeTo(workspace), mainModule)
  }

  private def toPackage(project0: Project): PackageSpec = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    val isKotlin = isKotlinProject(project0)
    val kotlinVersionOpt = if (isKotlin) detectKotlinVersion(project0) else None
    var mainModule = ModuleSpec(
      name = moduleDir.last,
      repositories = getRepositories.asScala.toSeq.collect(toRepositoryUrlString).distinct
        .diff(Seq(
          getRepositories.mavenCentral,
          getRepositories.mavenLocal,
          getRepositories.gradlePluginPortal
        ).collect(toRepositoryUrlString))
    )

    var packageSpec = if (getPluginManager.hasPlugin("java-platform")) {
      val configs = getConfigurations.asScala.toSeq
      val deps = configs.flatMap(_.getDependencies.asScala)
      val constraints = configs.flatMap(_.getDependencyConstraints.asScala)
      mainModule = mainModule.copy(
        imports = "mill.javalib.*" +: mainModule.imports,
        supertypes = "JavaModule" +: "BomModule" +: mainModule.supertypes,
        bomMvnDeps = deps.filter(isBom).collect(toMvnDep),
        depManagement = constraints.collect(toMvnDep),
        moduleDeps = constraints.collect(toModuleDep),
        bomModuleDeps = deps.filter(isBom).collect(toModuleDep)
      )
      PackageSpec(moduleDir.subRelativeTo(workspace), mainModule)
    } else if (getPluginManager.hasPlugin("java") || isKotlin) {
      val data = extractJvmData(project0, isKotlin, kotlinVersionOpt)
      configureJvmModule(project0, data, mainModule, isKotlin, kotlinVersionOpt)
    } else {
      PackageSpec(moduleDir.subRelativeTo(workspace), mainModule)
    }

    for {
      pubExt <- Option(getExtensions.findByType(classOf[PublishingExtension]))
      pub <- pubExt.getPublications.withType(classOf[MavenPublication]).asScala.headOption
      pom = Option(pub.getPom)
    } do {
      val updatedMainModule = packageSpec.module.copy(
        imports = "mill.javalib.*" +: "mill.javalib.publish.*" +: packageSpec.module.imports,
        supertypes = packageSpec.module.supertypes :+ "PublishModule",
        artifactName = Option(pub.getArtifactId),
        pomPackagingType = pom.flatMap(toPomPackagingType),
        pomSettings = pom.map(toPomSettings(_, pub.getGroupId)),
        publishVersion = Option(getVersion).map(_.toString)
      )
      packageSpec = packageSpec.copy(module = updatedMainModule)
    }

    packageSpec
  }

  private val toRepositoryUrlString: PartialFunction[ArtifactRepository, String] = {
    case repo: UrlArtifactRepository => repo.getUrl.toURL.toExternalForm
  }

  private val platform = objectFactory.named(classOf[Category], Category.REGULAR_PLATFORM)
  private val enforcedPlatform = objectFactory.named(classOf[Category], Category.ENFORCED_PLATFORM)
  private val SpringBootPluginId = "org.springframework.boot"
  private val QuarkusPluginId = "io.quarkus"

  private def isSpringBootProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(SpringBootPluginId)

  private def isQuarkusProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(QuarkusPluginId)

  private val KotlinJvmPluginId = "org.jetbrains.kotlin.jvm"

  private def isKotlinProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(KotlinJvmPluginId) ||
      project.getPluginManager.hasPlugin("kotlin")

  /**
   * Detects the Kotlin version by using the `getPluginVersion` method on the Kotlin plugin, if present.
   */
  private def detectKotlinVersion(project: Project): Option[String] = {
    val pluginOpt = Option(project.getPlugins.findPlugin(KotlinJvmPluginId))
      .orElse(Option(project.getPlugins.findPlugin("kotlin")))
    pluginOpt.flatMap { plugin =>
      try {
        val method = plugin.getClass.getMethod("getPluginVersion")
        Option(method.invoke(plugin)).map(_.toString)
      } catch {
        case _: Throwable => None
      }
    }.orElse {
      // Fallback to implementation version and remove any "-release" suffix
      detectPluginVersion(project, KotlinJvmPluginId)
        .orElse(detectPluginVersion(project, "kotlin"))
        .map(_.split("-release").head)
    }
  }

  /**
   * Tries to detect the version of the given plugin
   * by looking at the implementation version of the plugin class's package.
   * Fallbacks to looking for the plugin in the buildscript classpath.
   */
  private def detectPluginVersion(project: Project, pluginId: String): Option[String] = {
    val pluginImplVersion = Option(project.getPlugins.findPlugin(pluginId))
      .flatMap(plugin => Option(plugin.getClass.getPackage))
      .flatMap(pkg => Option(pkg.getImplementationVersion))
      .filter(_.nonEmpty)
    val buildScriptVersion = project.getBuildscript.getConfigurations.getByName(
      "classpath"
    ).getResolvedConfiguration.getResolvedArtifacts.asScala
      .find(artifact => artifact.getModuleVersion.getId.getGroup == pluginId)
      .map(_.getModuleVersion.getId.getVersion)
    pluginImplVersion.orElse(buildScriptVersion)
  }

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
      Opt.groups(
        task.getOptions.getAllCompilerArgs.asScala.toSeq
          .filterNot(arg => isManagedJavacOption(arg) || isErrorProneOption(arg))
      )
  }

  private def isErrorProneOption(arg: String): Boolean = arg.startsWith("-Xplugin:ErrorProne")

  private def errorProneOptions(task: JavaCompile): Seq[String] =
    task.getOptions.getAllCompilerArgs.asScala.toSeq
      .collectFirst {
        case arg if isErrorProneOption(arg) => arg.split("\\s+").toSeq.tail
      }.getOrElse(Nil)

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
