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
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.{JavaPluginExtension, ExtensionAware}
import org.gradle.api.provider.Provider
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

  private def getDeps(data: ExtractedJvmData, configNames: String*): Seq[Dependency] = {
    data.configs
      .filter(config => configNames.contains(config.getName))
      .flatMap(_.getDependencies.asScala)
  }

  private def getMvnDeps(
      data: ExtractedJvmData,
      configNames: String*
  ): Seq[MvnDep] = {
    getDeps(data, configNames*).filterNot(isBom).collect(toMvnDep).distinct
  }

  private def getModuleDeps(
      data: ExtractedJvmData,
      configNames: String*
  ): Seq[ModuleDep] = {
    getDeps(data, configNames*).filterNot(isBom).collect(toModuleDep).distinct
  }

  private def tryReflect[T](label: String)(thunk: => T): Option[T] =
    Try(thunk).fold(
      err => {
        println(s"Warning: could not resolve $label: $err")
        None
      },
      v => Option(v)
    )

  private def reflectGet[T](obj: Any, getterName: String): Option[T] =
    tryReflect(getterName)(obj.getClass.getMethod(getterName).invoke(obj).asInstanceOf[T])

  private def kotlinOptions(task: Task): Seq[String] =
    // getFreeCompilerArgs returns a Gradle ListProperty<String>, so we call .get() on it.
    reflectGet[Any](task, "getCompilerOptions")
      .flatMap(reflectGet[Any](_, "getFreeCompilerArgs"))
      .flatMap(reflectGet[java.util.List[String]](_, "get"))
      .fold(Nil)(_.asScala.toSeq)

  private case class ExtractedJvmData(
      configs: Seq[Configuration],
      mainConfigs: Seq[Configuration],
      testMvnDepsList: Seq[MvnDep],
      testMixin: Seq[String],
      testBomDeps: Seq[Dependency],
      testConstraints: Seq[DependencyConstraint],
      testJavaCompile: Option[JavaCompile],
      mainJavaCompile: Option[JavaCompile],
      effectiveBomDeps: Seq[Dependency],
      mainConstraints: Seq[DependencyConstraint],
      forkArgs: Seq[Opt],
      kotlinData: Option[KotlinData],
      frameworkData: FrameworkData,
      publishData: PublishData
  )

  private case class KotlinData(
      kotlinVersion: Option[String],
      mainKotlinCompile: Option[Task],
      testKotlinCompile: Option[Task],
      mainKotlinPluginDeps: Seq[MvnDep],
      kotlinTestResolvedNameOpt: Option[String]
  )

  private case class FrameworkData(
      isSpringBoot: Boolean,
      springBootVersion: Option[String],
      isQuarkus: Boolean,
      quarkusVersion: Option[String],
      isMicronautAot: Boolean,
      micronautVersion: Option[String],
      micronautPackage: Option[String],
      micronautAotConfigFile: Option[String],
      micronautAotConfigProperties: Option[Map[String, String]],
      hasErrorPronePlugin: Boolean
  )

  private case class PublishData(
      artifactName: Option[String],
      publishVersion: Option[String],
      pomSettings: Option[PomSettings]
  )

  private case class AndroidAppData(
      namespace: Option[String],
      applicationId: Option[String],
      compileSdk: Option[Int],
      minSdk: Option[Int],
      targetSdk: Option[Int],
      versionCode: Option[Int],
      versionName: Option[String],
      buildToolsVersion: Option[String]
  )

  private def extractJvmData(project0: Project, isKotlin: Boolean): ExtractedJvmData = {
    import project0.*

    def getTask[T](name: String)(using T: TypeTest[Task, T]) =
      getTasks.findByName(name) match {
        case T(t) => Some(t)
        case _ => None
      }

    val configs = getConfigurations.asScala.toSeq

    val testMvnDepsList = configs.find(_.getName == "testRuntimeClasspath")
      .fold(Nil)(_.getAllDependencies.asScala.toSeq.collect(toMvnDep))

    val testMixin = ModuleSpec.testModuleMixin(testMvnDepsList)
    val (testConfigs, mainConfigs) = configs.partition(_.getName.startsWith("test"))
    val testBomDeps = testConfigs.flatMap(_.getDependencies.asScala).filter(isBom)
    val testConstraints = testConfigs.flatMap(_.getDependencyConstraints.asScala)

    val mainJavaCompile = getTask[JavaCompile]("compileJava")
    val testJavaCompile = getTask[JavaCompile]("compileTestJava")
    val mainBomDeps = mainConfigs.flatMap(_.getDependencies.asScala).filter(isBom)
    val mainConstraints = mainConfigs.flatMap(_.getDependencyConstraints.asScala)

    val forkArgs = getTask[Test]("test").fold(Nil) { task =>
      task.getSystemProperties.asScala.map {
        case (k, v) => Opt(s"-D$k=$v")
      }.toSeq ++ Opt.groups(task.getJvmArgs.asScala.toSeq)
    }

    val kotlinDataOpt = Option.when(isKotlin) {

      // Use getFirstLevelModuleDependencies() to avoid picking up transitive deps of the infra plugin.
      // excluding kotlin-scripting-compiler-embeddable which is always present as Kotlin infrastructure.
      val kotlinCompilerPlugins = {
        val configName = "kotlinCompilerPluginClasspathMain"
        Option(getConfigurations.findByName(configName))
          .flatMap(config =>
            Try(config.getResolvedConfiguration.getFirstLevelModuleDependencies.asScala).fold(
              _ => {
                println(
                  s"Warning: could not resolve '$configName', skipping Kotlin compiler plugins"
                ); None
              },
              v => Some(v)
            )
          )
          .getOrElse(Set.empty[ResolvedDependency])
          .filterNot(_.getModuleName == "kotlin-scripting-compiler-embeddable")
          .map(dep => MvnDep(dep.getModuleGroup, dep.getModuleName, dep.getModuleVersion))
          .toSeq.distinct
      }

      // org.jetbrains.kotlin:kotlin-test is a multiplatform POM that lacks JVM classes.
      // We find the actual platform variant (e.g. kotlin-test-junit5)
      // resolved by Gradle's test runtime classpath.
      val kotlinTestResolvedNameOpt = {
        val configName = "testRuntimeClasspath"
        testConfigs.find(_.getName == configName)
          .flatMap { config =>
            Try(config.getResolvedConfiguration.getResolvedArtifacts.asScala).fold(
              _ => {
                println(
                  s"Warning: could not resolve '$configName', kotlin-test variant will not be detected"
                ); None
              },
              v => Some(v)
            ).flatMap { artifacts =>
              artifacts.find(art =>
                art.getModuleVersion.getId.getGroup == "org.jetbrains.kotlin" &&
                  art.getModuleVersion.getId.getName.startsWith("kotlin-test-")
              ).map(_.getModuleVersion.getId.getName)
            }
          }
      }
      KotlinData(
        kotlinVersion = detectKotlinVersion(project0, configs),
        mainKotlinCompile = Option(getTasks.findByName("compileKotlin")),
        testKotlinCompile = Option(getTasks.findByName("compileTestKotlin")),
        mainKotlinPluginDeps = kotlinCompilerPlugins,
        kotlinTestResolvedNameOpt = kotlinTestResolvedNameOpt
      )
    }

    val (isSpringBoot, springBootVersion) =
      if (isSpringBootProject(project0)) {
        (true, detectPluginVersion(project0, SpringBootPluginId))
      } else (false, None)
    val (isQuarkus, quarkusVersion) =
      if (isQuarkusProject(project0)) {
        (true, detectPluginVersion(project0, QuarkusPluginId))
      } else (false, None)
    val isMicronautAot = isMicronautAotProject(project0)
    val (mnVersion, mnPkg, mnConfigFile, mnConfigProps) =
      if (isMicronautAot) detectMicronautAot(project0)
      else (None, None, None, None)

    val hasErrorPronePlugin = getPluginManager.hasPlugin("net.ltgt.errorprone")

    val frameworkData = FrameworkData(
      isSpringBoot = isSpringBoot,
      springBootVersion = springBootVersion,
      isQuarkus = isQuarkus,
      quarkusVersion = quarkusVersion,
      isMicronautAot = isMicronautAot,
      micronautVersion = mnVersion,
      micronautPackage = mnPkg,
      micronautAotConfigFile = mnConfigFile,
      micronautAotConfigProperties = mnConfigProps,
      hasErrorPronePlugin = hasErrorPronePlugin
    )

    val publishData = PublishData(
      artifactName = Option(getName),
      publishVersion = Option(getVersion).map(_.toString),
      pomSettings = Some(PomSettings(
        organization = getGroup.toString,
        description = Option(getDescription).getOrElse("")
      ))
    )

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
      mainConfigs = mainConfigs,
      testMvnDepsList = testMvnDepsList,
      testMixin = testMixin.toSeq,
      testBomDeps = testBomDeps,
      testConstraints = testConstraints,
      testJavaCompile = testJavaCompile,
      mainJavaCompile = mainJavaCompile,
      effectiveBomDeps = effectiveBomDeps,
      mainConstraints = mainConstraints,
      forkArgs = forkArgs,
      kotlinData = kotlinDataOpt,
      frameworkData = frameworkData,
      publishData = publishData
    )
  }

  private def configureBaseJvmModule(
      data: ExtractedJvmData,
      mainModule0: ModuleSpec
  ): ModuleSpec = {
    var module = mainModule0.copy(
      mvnDeps = getMvnDeps(data, "implementation", "api"),
      compileMvnDeps = getMvnDeps(data, "compileOnly", "compileOnlyApi"),
      runMvnDeps = getMvnDeps(data, "runtimeOnly"),
      bomMvnDeps = data.effectiveBomDeps.collect(toMvnDep).distinct,
      depManagement = data.mainConstraints.collect(toMvnDep).distinct,
      javacOptions = data.mainJavaCompile.fold(Nil)(javacOptions),
      moduleDeps = getModuleDeps(data, "implementation", "api"),
      compileModuleDeps = getModuleDeps(data, "compileOnly", "compileOnlyApi"),
      runModuleDeps = getModuleDeps(data, "runtimeOnly"),
      bomModuleDeps = data.mainConfigs.flatMap(
        _.getDependencies.asScala
      ).filter(isBom).collect(toModuleDep).distinct,
      annotationProcessorsMvnDeps = getMvnDeps(data, "annotationProcessor")
    )

    data.kotlinData.foreach { kd =>
      module = module.copy(
        kotlinVersion = kd.kotlinVersion,
        kotlincOptions = kd.mainKotlinCompile.fold(Nil)(task => Opt.groups(kotlinOptions(task))),
        kotlincPluginMvnDeps = kd.mainKotlinPluginDeps
      )
    }

    module
  }

  private def configureJvmModule(
      moduleDir: os.Path,
      data: ExtractedJvmData,
      mainModule0: ModuleSpec
  ): PackageSpec = {
    val isKotlin = data.kotlinData.isDefined

    val baseJvmModule =
      configureBaseJvmModule(data, mainModule0)

    var mainModule = baseJvmModule.copy(
      imports =
        (if (isKotlin) Seq("mill.kotlinlib.*", "mill.javalib.*")
         else Seq("mill.javalib.*")) ++ baseJvmModule.imports,
      supertypes =
        (if (isKotlin) "KotlinMavenModule" else "MavenModule") +: baseJvmModule.supertypes
    )

    if (data.frameworkData.isSpringBoot) {
      mainModule = mainModule.withSpringBootModule(data.frameworkData.springBootVersion)
    }

    if (data.frameworkData.isQuarkus) {
      mainModule =
        mainModule.withQuarkusModule(
          data.frameworkData.quarkusVersion,
          data.publishData.pomSettings.map(_.organization)
        )

      // Add PublishModule and artifact/pom settings.
      mainModule = mainModule.copy(
        imports = "mill.javalib.publish.*" +: mainModule.imports,
        supertypes = mainModule.supertypes :+ "PublishModule",
        artifactName = data.publishData.artifactName,
        publishVersion = data.publishData.publishVersion,
        pomSettings = data.publishData.pomSettings
      )
    }

    if (data.frameworkData.isMicronautAot) {
      mainModule = mainModule.withMicronautAotModule(
        micronautVersion = Value(data.frameworkData.micronautVersion),
        micronautPackage = Value(data.frameworkData.micronautPackage),
        micronautAotConfigFile = Value(data.frameworkData.micronautAotConfigFile),
        micronautAotConfigProperties = Value(data.frameworkData.micronautAotConfigProperties)
      )
    }

    if (data.frameworkData.hasErrorPronePlugin) {
      mainModule = mainModule.withErrorProneModule(
        errorProneMvnDeps = getMvnDeps(data, "errorprone"),
        errorProneOptions = data.mainJavaCompile.fold(Nil)(errorProneOptions)
      )
    }

    if (os.exists(moduleDir / "src/test")) {
      val testBomDeps = data.testBomDeps
      val testConstraints = data.testConstraints

      var testModule = ModuleSpec(
        name = "test",
        supertypes = (if (isKotlin) "KotlinMavenTests" else "MavenTests") +: data.testMixin,
        forkArgs = Values(
          data.forkArgs,
          appendSuper = true
        ),
        forkWorkingDir = Some("moduleDir"),
        mvnDeps = {
          val deps = getMvnDeps(data, "testImplementation")
          // Change the name of the kotlin-test dependency to the resolved name, if available
          val resolvedNameOpt = data.kotlinData.flatMap(_.kotlinTestResolvedNameOpt)
          if (resolvedNameOpt.isDefined) {
            deps.map { dep =>
              if (dep.organization == "org.jetbrains.kotlin" && dep.name == "kotlin-test") {
                val version =
                  if (dep.version.nonEmpty) dep.version
                  else data.kotlinData.flatMap(_.kotlinVersion).getOrElse("")
                dep.copy(name = resolvedNameOpt.get, version = version)
              } else {
                dep
              }
            }.distinct
          } else deps
        },
        compileMvnDeps = getMvnDeps(data, "testCompileOnly"),
        runMvnDeps = getMvnDeps(data, "testRuntimeOnly"),
        bomMvnDeps = testBomDeps.collect(toMvnDep).distinct,
        depManagement = testConstraints.collect(toMvnDep).distinct,
        javacOptions = data.testJavaCompile.fold(Nil)(javacOptions),
        moduleDeps = Values(
          getModuleDeps(data, "testImplementation")
            .diff(Seq(ModuleDep(moduleDir.subRelativeTo(workspace).segments))),
          appendSuper = true
        ),
        compileModuleDeps = getModuleDeps(data, "testCompileOnly"),
        runModuleDeps = getModuleDeps(data, "testRuntimeOnly"),
        bomModuleDeps = testBomDeps.collect(toModuleDep).distinct,
        testParallelism = Some(false),
        testSandboxWorkingDir = Some(false),
        testFramework = Option.when(data.testMixin.isEmpty)(""),
        annotationProcessorsMvnDeps = getMvnDeps(data, "testAnnotationProcessor"),
        kotlincOptions = data.kotlinData.map(_.testKotlinCompile.fold(Nil)(task =>
          Opt.groups(kotlinOptions(task))
        )).getOrElse(Nil)
      )
      if (data.frameworkData.hasErrorPronePlugin) {
        testModule = testModule.withErrorProneModule(
          errorProneMvnDeps = mainModule.errorProneDeps,
          errorProneOptions = data.testJavaCompile.fold(Nil)(errorProneOptions)
        )
      }
      if (data.frameworkData.isSpringBoot) {
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

  /**
   * Reads the `android { ... }` extension via reflection rather than a compile-time AGP
   * dependency, since `exportplugin` must work against whatever AGP version the target
   * project applies.
   */
  private def extractAndroidAppData(project: Project): AndroidAppData = {
    val androidExt = Option(project.getExtensions.findByName("android"))
    val defaultConfig = androidExt.flatMap(reflectGet[Any](_, "getDefaultConfig"))

    AndroidAppData(
      namespace = androidExt.flatMap(reflectGet[String](_, "getNamespace")),
      applicationId = defaultConfig.flatMap(reflectGet[String](_, "getApplicationId")),
      compileSdk = androidExt.flatMap(reflectGet[Integer](_, "getCompileSdk")).map(_.intValue),
      minSdk = defaultConfig.flatMap(reflectGet[Integer](_, "getMinSdk")).map(_.intValue),
      targetSdk = defaultConfig.flatMap(reflectGet[Integer](_, "getTargetSdk")).map(_.intValue),
      versionCode = defaultConfig.flatMap(reflectGet[Integer](_, "getVersionCode")).map(_.intValue),
      versionName = defaultConfig.flatMap(reflectGet[String](_, "getVersionName")),
      buildToolsVersion = androidExt.flatMap(reflectGet[String](_, "getBuildToolsVersion"))
    )
  }

  private def configureAndroidAppModule(
      moduleDir: os.Path,
      data: ExtractedJvmData,
      androidData: AndroidAppData,
      mainModule0: ModuleSpec
  ): PackageSpec = {
    import androidData.*
    val baseJvmModule = configureBaseJvmModule(data, mainModule0)

    var mainModule = baseJvmModule.withAndroidKotlinModule(
      isApp = true,
      namespace = namespace,
      applicationId = applicationId,
      compileSdk = compileSdk,
      minSdk = minSdk,
      targetSdk = targetSdk,
      versionCode = versionCode,
      versionName = versionName,
      buildToolsVersion = buildToolsVersion
    )

    if (os.exists(moduleDir / "src/test")) {
      // Android has no plain "testRuntimeClasspath" config (unlike plain Java/Kotlin) - unit
      // test configs are build-type-qualified, e.g. "debugUnitTestRuntimeClasspath".
      val androidTestMvnDepsList = data.configs.find(_.getName == "debugUnitTestRuntimeClasspath")
        .fold(Nil)(_.getAllDependencies.asScala.toSeq.collect(toMvnDep))
      val androidTestMixin = ModuleSpec.testModuleMixin(androidTestMvnDepsList)
      val testModule = ModuleSpec(
        name = "test",
        supertypes = "AndroidAppKotlinTests" +: androidTestMixin.toSeq,
        mvnDeps = getMvnDeps(data, "testImplementation"),
        compileMvnDeps = getMvnDeps(data, "testCompileOnly"),
        runMvnDeps = getMvnDeps(data, "testRuntimeOnly"),
        moduleDeps = getModuleDeps(data, "testImplementation")
          .diff(Seq(ModuleDep(moduleDir.subRelativeTo(workspace).segments))),
        compileModuleDeps = getModuleDeps(data, "testCompileOnly"),
        runModuleDeps = getModuleDeps(data, "testRuntimeOnly"),
        testParallelism = Some(false),
        testSandboxWorkingDir = Some(false),
        testFramework = Option.when(androidTestMixin.isEmpty)("")
      )
      mainModule = mainModule.copy(children = mainModule.children :+ testModule)
    }

    if (os.exists(moduleDir / "src/androidTest")) {
      val androidTestModule = ModuleSpec(
        name = "it",
        supertypes = Seq("AndroidAppKotlinInstrumentedTests"),
        mvnDeps = getMvnDeps(data, "androidTestImplementation"),
        compileMvnDeps = getMvnDeps(data, "androidTestCompileOnly"),
        runMvnDeps = getMvnDeps(data, "androidTestRuntimeOnly"),
        moduleDeps = getModuleDeps(data, "androidTestImplementation")
          .diff(Seq(ModuleDep(moduleDir.subRelativeTo(workspace).segments))),
        compileModuleDeps = getModuleDeps(data, "androidTestCompileOnly"),
        runModuleDeps = getModuleDeps(data, "androidTestRuntimeOnly")
      )
      mainModule = mainModule.copy(children = mainModule.children :+ androidTestModule)
    }

    PackageSpec(moduleDir.subRelativeTo(workspace), mainModule)
  }

  private def toPackage(project0: Project): PackageSpec = {
    import project0.*
    val moduleDir = os.Path(getProjectDir)
    val isKotlin = isKotlinProject(project0)
    var mainModule = ModuleSpec(
      name = moduleDir.last,
      repositories = getRepositories.asScala.toSeq
        .filterNot(repo => WellKnownRepositoryNames.contains(repo.getName))
        .collect(toRepositoryUrlString).distinct
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
    } else if (isAndroidAppProject(project0)) {
      // Apply the Kotlin module by default, matching AGP's own latest behavior.
      val data = extractJvmData(project0, isKotlin = true)
      val androidData = extractAndroidAppData(project0)
      configureAndroidAppModule(moduleDir, data, androidData, mainModule)
    } else if (getPluginManager.hasPlugin("java") || isKotlin) {
      val data = extractJvmData(project0, isKotlin)
      configureJvmModule(moduleDir, data, mainModule)
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

  /**
   * Gradle's own default names for `mavenCentral()`/`mavenLocal()`/`gradlePluginPortal()`. We
   * filter by name rather than by calling those `RepositoryHandler` methods for their URLs,
   * since calling them adds the repository to the project as a side effect.
   * That throws under `dependencyResolutionManagement { repositoriesMode.set(FAIL_ON_PROJECT_REPOS) }`.
   */
  private val WellKnownRepositoryNames = Set(
    ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME,
    ArtifactRepositoryContainer.DEFAULT_MAVEN_LOCAL_REPO_NAME,
    "Gradle Central Plugin Repository"
  )

  private val platform = objectFactory.named(classOf[Category], Category.REGULAR_PLATFORM)
  private val enforcedPlatform = objectFactory.named(classOf[Category], Category.ENFORCED_PLATFORM)
  private val SpringBootPluginId = "org.springframework.boot"
  private val QuarkusPluginId = "io.quarkus"
  private val MicronautAotPluginId = "io.micronaut.aot"
  private val MicronautApplicationPluginId = "io.micronaut.application"

  private def isSpringBootProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(SpringBootPluginId)

  private def isQuarkusProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(QuarkusPluginId)

  private def isMicronautAotProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(MicronautAotPluginId) ||
      project.getPluginManager.hasPlugin(MicronautApplicationPluginId)

  private val AotBooleanProps = List(
    "cacheEnvironment",
    "convertYamlToJava",
    "deduceEnvironment",
    "optimizeClassLoading",
    "optimizeNetty",
    "optimizeServiceLoading",
    "precomputeOperations",
    "replaceLogbackXml"
  )

  private def providerValue[T](obj: Any, getterName: String): Option[T] =
    tryReflect(s"Micronaut AOT $getterName") {
      obj.getClass.getMethod(getterName).invoke(obj).asInstanceOf[Provider[T]].getOrNull()
    }

  private def detectMicronautAot(project: Project)
      : (Option[String], Option[String], Option[String], Option[Map[String, String]]) = {
    def prop(key: String): Option[String] =
      Option(project.findProperty(key)).map(_.toString).filter(_.nonEmpty)

    val ver = prop("micronautVersion")

    val micronautExt = Option(project.getExtensions.findByName("micronaut"))

    val aotExt: Option[Any] = micronautExt.collect {
      case ea: ExtensionAware => ea.getExtensions.findByName("aot")
    }.flatMap(Option(_))

    val pkg = prop("micronaut.aot.packageName")
      .orElse(aotExt.flatMap(providerValue[String](_, "getTargetPackage")))

    val configFile = aotExt.flatMap { aot =>
      providerValue[RegularFile](aot, "getConfigFile")
        .map(_.getAsFile)
        .collect {
          case f if f.exists() =>
            val projectDir = os.Path(project.getProjectDir)
            val filePath = os.Path(f)
            if (filePath.startsWith(projectDir)) filePath.subRelativeTo(projectDir).toString
            else f.getName
        }
    }

    val aotConfigProps = aotExt.flatMap { aot =>
      val getters = AotBooleanProps.map(name =>
        name -> s"get${name.head.toUpper}${name.tail}"
      ) :+ ("version" -> "getVersion")
      val props = getters.flatMap { case (name, getter) =>
        providerValue[Any](aot, getter).map(v => name -> v.toString)
      }.toMap
      Option.when(props.nonEmpty)(props)
    }

    (ver, pkg, configFile, aotConfigProps)
  }

  private val KotlinJvmPluginId = "org.jetbrains.kotlin.jvm"
  private val KotlinAndroidPluginId = "org.jetbrains.kotlin.android"

  private def isKotlinProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(KotlinJvmPluginId) ||
      project.getPluginManager.hasPlugin(KotlinAndroidPluginId) ||
      project.getPluginManager.hasPlugin("kotlin")

  /**
   * Detects the Kotlin version by using the `getPluginVersion` method on the Kotlin plugin, if
   * present. Fallbacks to kotlin-stdlib version (AGP 9).
   */
  private def detectKotlinVersion(project: Project, configs: Seq[Configuration]): Option[String] = {
    val pluginOpt = Option(project.getPlugins.findPlugin(KotlinJvmPluginId))
      .orElse(Option(project.getPlugins.findPlugin(KotlinAndroidPluginId)))
      .orElse(Option(project.getPlugins.findPlugin("kotlin")))
    val viaPluginVersion = pluginOpt.flatMap(reflectGet[Any](_, "getPluginVersion")).map(_.toString)
    viaPluginVersion.orElse {
      configs.iterator
        .flatMap(_.getDependencies.asScala)
        .collect(toMvnDep)
        .find(d => d.organization == "org.jetbrains.kotlin" && d.name.startsWith("kotlin-stdlib"))
        .map(_.version).filter(_.nonEmpty)
    }.orElse {
      // Fallback to implementation version and remove any "-release" suffix
      detectPluginVersion(project, KotlinJvmPluginId)
        .orElse(detectPluginVersion(project, KotlinAndroidPluginId))
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

  private val AndroidApplicationPluginId = "com.android.application"

  private def isAndroidAppProject(project: Project): Boolean =
    project.getPluginManager.hasPlugin(AndroidApplicationPluginId)

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
