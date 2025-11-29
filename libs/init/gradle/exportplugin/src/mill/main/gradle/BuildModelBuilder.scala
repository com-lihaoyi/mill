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
import org.gradle.api.plugins.JavaPlatformPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.*
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPom
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import java.io.File
import javax.inject.Inject
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
      repositories = getRepositories.iterator.asScala.collect(toRepositoryUrlString).distinct.toSeq
        .diff(Seq(
          getRepositories.mavenCentral,
          getRepositories.mavenLocal,
          getRepositories.gradlePluginPortal
        ).collect(toRepositoryUrlString))
    )

    def configurations(names: String*) = {
      val itr = getConfigurations.iterator.asScala
      if (names.isEmpty) itr else itr.filter(config => names.contains(config.getName))
    }
    def constraints =
      configurations().flatMap(_.getDependencyConstraints.iterator.asScala)
    def dependencies(configs: String*) =
      configurations(configs*).flatMap(_.getDependencies.iterator.asScala)
    def task[T](name: String)(using T: TypeTest[Task, T]) = getTasks.findByName(name) match {
      case T(t) => Some(t)
      case _ => None
    }
    val mavenPublication = Option(getExtensions.findByType(classOf[PublishingExtension]))
      .flatMap(ext => ext.getPublications.withType(classOf[MavenPublication]).asScala.headOption)

    if (getPluginManager.hasPlugin("java-platform")) {
      val (constrainedModules, constrainedDeps) =
        constraints.toSeq.partition(toModuleDep.isDefinedAt)
      val (modulesDeps, bomModuleDeps) = constrainedModules.partitionMap(dep =>
        Either.cond(isBom(dep), toModuleDep(dep), toModuleDep(dep))
      )
      val (bomModuleDeps1, bomDeps) =
        dependencies().filter(isBom).toSeq.partitionMap(dep => toModuleDep.lift(dep).toLeft(dep))
      mainModule = mainModule.copy(
        imports = "import mill.javalib.*" +: mainModule.imports,
        supertypes = "JavaModule" +: "BomModule" +: mainModule.supertypes,
        bomMvnDeps = bomDeps.collect(toMvnDep),
        depManagement = constrainedDeps.collect(toMvnDep),
        moduleDeps = modulesDeps,
        bomModuleDeps = bomModuleDeps ++ bomModuleDeps1
      )
    } else if (getPluginManager.hasPlugin("java")) {
      def mvnDeps(configs: String*) =
        dependencies(configs*).filterNot(isBom).collect(toMvnDep).toSeq
      def moduleDeps(configs: String*) =
        dependencies(configs*).filterNot(isBom).collect(toModuleDep).toSeq
      val (bomModuleDeps, bomDeps) =
        dependencies().filter(isBom).toSeq.partitionMap(dep => toModuleDep.lift(dep).toLeft(dep))
      val javaPluginExt = getExtensions.findByType(classOf[JavaPluginExtension])
      val sourceSets = ctx.sourceSets(javaPluginExt)
      val (sourcesFolders, sources, resources) =
        sourceSets.find(_.getName == "main").fold((Nil, Nil, Nil))(ss =>
          val (sourcesFolders, sources) =
            ss.getJava.getSrcDirs.iterator.asScala
              .map(os.Path(_).relativeTo(moduleDir)).toSeq.partition(_.ups == 0)
          val resources = ss.getResources.getSrcDirs.iterator.asScala
            .map(os.Path(_).relativeTo(moduleDir)).toSeq
          (
            if (sourcesFolders == Seq(os.rel / "src/main/java")) Nil
            else sourcesFolders.map(_.asSubPath),
            sources,
            if (resources == Seq(os.rel / "src/main/resources")) Nil else resources
          )
        )
      mainModule = mainModule.copy(
        imports = "import mill.javalib.*" +: mainModule.imports,
        supertypes = "MavenModule" +: mainModule.supertypes,
        mvnDeps = mvnDeps("implementation", "api"),
        compileMvnDeps = mvnDeps("compileOnly", "compileOnlyApi"),
        runMvnDeps = mvnDeps("runtimeOnly"),
        bomMvnDeps = bomDeps.collect(toMvnDep),
        depManagement = constraints.collect(toMvnDep).toSeq,
        javacOptions = task[JavaCompile]("compileJava").fold(Nil)(javacOptions),
        sourcesFolders = sourcesFolders,
        sources = Values(extend = true, sources),
        resources = resources,
        moduleDeps = moduleDeps("implementation", "api"),
        compileModuleDeps = moduleDeps("compileOnly", "compileOnlyApi"),
        runModuleDeps = moduleDeps("runtimeOnly"),
        bomModuleDeps = bomModuleDeps,
        artifactName = mavenPublication.map(_.getArtifactId)
      ).withErrorProneModule(mvnDeps("errorprone"))

      for {
        testSourceSet <- sourceSets.find(_.getName == "test")
        if testSourceSet.getJava.getSrcDirs.asScala.exists(_.exists)
      } do {
        val testMvnDeps = mvnDeps("testImplementation")
        ModuleSpec.testModuleMixin(testMvnDeps).foreach { mixin =>
          val (sourcesFolders, sources) = testSourceSet.getJava.getSrcDirs.iterator.asScala
            .map(os.Path(_).relativeTo(moduleDir)).toSeq.partition(_.ups == 0)
          val resources = testSourceSet.getResources.getSrcDirs.iterator.asScala
            .map(os.Path(_).relativeTo(moduleDir)).toSeq
          var testModule = ModuleSpec(
            name = "test",
            supertypes = Seq("MavenTests"),
            mixins = Seq(mixin),
            forkArgs = task[Test]("test").fold(Nil)(task =>
              task.getSystemProperties().asScala.map {
                case (k, v) => Opt(s"-D$k=$v")
              }.toSeq ++ Opt.groups(task.getJvmArgs.asScala.toSeq)
            ),
            forkWorkingDir = Some(os.rel),
            mvnDeps = testMvnDeps,
            compileMvnDeps = mvnDeps("testCompileOnly"),
            runMvnDeps = mvnDeps("testRuntimeOnly"),
            javacOptions = task[JavaCompile]("compileTestJava").fold(Nil)(javacOptions),
            sourcesFolders = if (sourcesFolders == Seq(os.rel / "src/test/java")) Nil
            else sourcesFolders.map(_.asSubPath),
            sources = Values(extend = true, sources),
            resources = if (resources == Seq(os.rel / "src/test/resources")) Nil else resources,
            moduleDeps = Values(
              extend = true,
              moduleDeps("testImplementation")
                .diff(Seq(ModuleDep(moduleDir.subRelativeTo(workspace))))
            ),
            compileModuleDeps = moduleDeps("testCompileOnly"),
            runModuleDeps = moduleDeps("testRuntimeOnly"),
            testParallelism = Some(false),
            testSandboxWorkingDir = Some(false)
          ).withErrorProneModule(mainModule.errorProneDeps.base)

          mixin match {
            // Gradle can resolve junit-platform-launcher version using junit-bom transitive
            // dependency. Since Coursier cannot do the same, make the dependency explicit.
            case "TestModule.Junit5"
                if !mainModule.depManagement.base.exists(dep =>
                  dep.name == "junit-platform-launcher" && dep.organization == "org.junit.platform"
                ) =>
              val jplOpt = testModule.runMvnDeps.base.find(dep =>
                dep.name == "junit-platform-launcher" && dep.organization == "org.junit.platform"
              )
              if (jplOpt.forall(_.version.isEmpty)) {
                // Some legacy Gradle versions auto-include junit-platform-launcher.
                if (jplOpt.isEmpty) {
                  val jpl = MvnDep("org.junit.platform", "junit-platform-launcher", "")
                  testModule = testModule.copy(runMvnDeps = jpl +: testModule.runMvnDeps.base)
                }
                if (
                  !mainModule.bomMvnDeps.base.exists(dep =>
                    dep.name == "junit-bom" && dep.organization == "org.junit"
                  )
                ) {
                  testModule.mvnDeps.base.collectFirst {
                    case dep if dep.organization == "org.junit.jupiter" && dep.version.nonEmpty =>
                      dep.version
                  }.foreach { junitVersion =>
                    val junitBom = MvnDep("org.junit", "junit-bom", junitVersion)
                    testModule = testModule.copy(bomMvnDeps =
                      testModule.bomMvnDeps.copy(base = junitBom +: testModule.bomMvnDeps.base)
                    )
                  }
                }
              }
            case _ =>
          }

          mainModule = mainModule.copy(test = Some(testModule))
        }
      }
    }

    mavenPublication.foreach { pub =>
      val pom = Option(pub.getPom)
      mainModule = mainModule.copy(
        imports = "import mill.javalib.*" +: "import mill.javalib.publish.*" +: mainModule.imports,
        supertypes = mainModule.supertypes :+ "PublishModule",
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
    case dep: DependencyConstraint =>
      import dep.*
      MvnDep(
        organization = getGroup,
        name = getName,
        version = toCoursierVersionConstraint(getVersion)
      )
  }

  private val toModuleDep: PartialFunction[Dependency | DependencyConstraint, ModuleDep] = {
    case dep: ProjectDependency =>
      ModuleDep(os.Path(ctx.project(dep).getProjectDir).subRelativeTo(workspace))
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
