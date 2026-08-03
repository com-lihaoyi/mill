package mill.main.maven

import mill.main.buildgen.*
import mill.main.buildgen.ModuleSpec.*
import org.apache.maven.model.{Developer as MvnDeveloper, License as MvnLicense, *}

import scala.jdk.CollectionConverters.*

object MillMavenBuildGenMain {

  def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args.toSeq)

  @mainargs.main(doc = "Generates Mill build files that are derived from a Maven build.")
  def init(
      @mainargs.arg(doc = "include properties from pom.xml in the generated build")
      publishProperties: mainargs.Flag,
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag,
      @mainargs.arg(doc = "Coursier JVM ID to assign to mill-jvm-version key in the build header")
      millJvmId: Option[String],
      @mainargs.arg(doc = "Generate declarative (YAML) or programmable (Scala) build files")
      declarative: Boolean = true,
      @mainargs.arg(doc =
        "The Maven project directory to migrate. Default is the current working directory."
      )
      projectDir: String = "."
  ): Unit = {
    println("converting Maven build")

    val buildGen = if (declarative) BuildGenYaml else BuildGenScala
    val mvnWorkspace = os.Path.expandUser(projectDir, os.pwd)
    val millWorkspace = os.pwd

    val modelBuildingResults = Modeler(mvnWorkspace).buildAll()
    val moduleDepLookup: PartialFunction[Dependency, ModuleDep] = modelBuildingResults.map { mbr =>
      val model = mbr.getEffectiveModel
      val key = (model.getGroupId, model.getArtifactId, model.getVersion)
      val dep = ModuleDep(os.Path(model.getProjectDirectory).subRelativeTo(mvnWorkspace).segments)
      (key, dep)
    }.toMap.compose {
      case dep: Dependency => (dep.getGroupId, dep.getArtifactId, dep.getVersion)
    }
    def toMvnOrModuleDep(dep: Dependency) =
      Either.cond(moduleDepLookup.isDefinedAt(dep), moduleDepLookup(dep), toMvnDep(dep))

    var packages = modelBuildingResults.map { result =>
      val model = result.getEffectiveModel
      val moduleDir = os.Path(model.getProjectDirectory)
      val plugins = Plugins(model)
      var mainModule = ModuleSpec(
        name = moduleDir.last,
        repositories = model.getRepositories.asScala.collect {
          case repo if repo.getId != "central" => repo.getUrl
        }.toSeq
      )

      model.getPackaging match {
        case "pom" =>
          val dmOpt = Option(model.getDependencyManagement).map(filterFrameworkBomDeps)
          if (dmOpt.exists(!_.getDependencies.isEmpty)) {
            val (bomDeps, deps) =
              dmOpt.get.getDependencies.asScala.toSeq.partition(isBom)
            val (bomMvnDeps, bomModuleDeps) = bomDeps.partitionMap(toMvnOrModuleDep)
            val (depManagement, moduleDeps) = deps.partitionMap(toMvnOrModuleDep)
            mainModule = mainModule.copy(
              imports = "mill.javalib.*" +: mainModule.imports,
              supertypes = "JavaModule" +: "BomModule" +: mainModule.supertypes,
              bomMvnDeps = bomMvnDeps,
              depManagement = depManagement,
              moduleDeps = moduleDeps,
              bomModuleDeps = bomModuleDeps
            )
          }
        case _ =>
          val (mavenModuleDeps, mavenDeps) =
            model.getDependencies.asScala.toSeq.partition(moduleDepLookup.isDefinedAt)
          def mvnDeps(scope: String) = mavenDeps.collect {
            case dep if dep.getScope == scope => toMvnDep(dep)
          }
          def moduleDeps(scope: String) = mavenModuleDeps.collect {
            case dep if dep.getScope == scope => moduleDepLookup(dep)
          }
          val isSpringParentProject = isSpringBootProject(model, result.getRawModel)
          val springBootVersion = detectSpringBootVersion(model, result.getRawModel)
          val quarkusVersionOpt = detectQuarkusPluginVersion(model)

          val (bomMvnDeps, depManagement, bomModuleDeps) =
            Option(model.getDependencyManagement).map(filterFrameworkBomDeps).fold((
              Nil,
              Nil,
              Nil
            )) { dm =>
              collectDependencyManagement(dm, toMvnOrModuleDep, moduleDepLookup)
            }

          mainModule = mainModule.copy(
            imports = "mill.javalib.*" +: mainModule.imports,
            supertypes = "MavenModule" +: mainModule.supertypes,
            mvnDeps = mvnDeps("compile"),
            compileMvnDeps = mvnDeps("provided"),
            runMvnDeps = mvnDeps("runtime"),
            bomMvnDeps = bomMvnDeps,
            depManagement = depManagement,
            javacOptions = plugins.javacOptions,
            moduleDeps = moduleDeps("compile"),
            compileModuleDeps = moduleDeps("provided"),
            runModuleDeps = moduleDeps("runtime"),
            bomModuleDeps = bomModuleDeps,
            artifactName = Option(model.getArtifactId),
            annotationProcessorsMvnDeps = plugins.annotationProcessorsMvnDeps
          )
          if (plugins.isErrorProneEnabled) {
            mainModule = mainModule.withErrorProneModule(
              errorProneMvnDeps = plugins.errorProneMvnDeps,
              errorProneOptions = plugins.errorProneOptions
            )
          }
          if (isSpringParentProject) {
            mainModule = mainModule.withSpringBootModule(springBootVersion)
          }
          if (quarkusVersionOpt.isDefined) {
            mainModule = mainModule.withQuarkusModule(
              quarkusVersionOpt,
              Option(model.getGroupId).filter(_.nonEmpty)
            )
          }
          if (os.exists(moduleDir / "src/test")) {
            val testMvnDeps = mvnDeps("test")
            val testMixin = ModuleSpec.testModuleMixin(testMvnDeps)
            val testModuleDeps = mavenModuleDeps.collect {
              case dep if dep.getScope == "test" =>
                moduleDepLookup(dep)
                  .copy(childSegment = Option.when(dep.getType == "test-jar")("test"))
            }
            var testModule = ModuleSpec(
              name = "test",
              supertypes = "MavenTests" +: testMixin.toSeq,
              forkArgs = Values(plugins.testForkArgs, appendSuper = true),
              forkWorkingDir = Some("moduleDir"),
              mvnDeps = testMvnDeps,
              compileMvnDeps = mainModule.compileMvnDeps,
              runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
              moduleDeps = Values(testModuleDeps, appendSuper = true),
              compileModuleDeps = mainModule.compileModuleDeps,
              runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
              testParallelism = Some(false),
              testSandboxWorkingDir = Some(false),
              testFramework = Option.when(testMixin.isEmpty)("")
            )
            if (isSpringParentProject) {
              testModule = testModule.withSpringBootTestsModule(springBootVersion)
            }
            if (testMixin.contains("TestModule.Junit5")) {
              testModule.mvnDeps.base.collectFirst {
                case dep if dep.organization == "org.junit.jupiter" && dep.version.nonEmpty =>
                  val junitVersion = dep.version
                  testModule = testModule.withJupiterInterface(junitVersion)
                  val launcherDep = testModule.mvnDeps.base.find(
                    _.is("org.junit.platform", "junit-platform-launcher")
                  )
                  if (launcherDep.forall(_.version.isEmpty)) {
                    if (launcherDep.isEmpty) {
                      testModule = testModule.copy(runMvnDeps =
                        testModule.runMvnDeps.copy(testModule.runMvnDeps.base :+
                          MvnDep("org.junit.platform", "junit-platform-launcher", ""))
                      )
                    }
                    testModule = testModule.copy(bomMvnDeps =
                      Values(
                        Seq(MvnDep("org.junit", "junit-bom", junitVersion)),
                        appendSuper = true
                      )
                    )
                  }
              }
            }
            mainModule = mainModule.copy(children = Seq(testModule))
          }
      }
      if (!plugins.skipDeploy) {
        mainModule = mainModule.copy(
          imports = "mill.javalib.*" +: "mill.javalib.publish.*" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "PublishModule",
          pomPackagingType = Option(model.getPackaging).filter(_ != "jar"),
          pomParentProject = toPomParentProject(model.getParent),
          // Use raw model since the effective one returns derived values for URL fields.
          pomSettings = Some(toPomSettings(result.getRawModel)),
          publishVersion = Option(model.getVersion),
          publishProperties =
            if (publishProperties.value) model.getProperties.asScala.toSeq else Nil
        )
      }
      PackageSpec(moduleDir.subRelativeTo(mvnWorkspace), mainModule)
    }
    packages = normalizeBuild(packages)

    val (baseModule, packages0) =
      if (noMeta.value) (None, packages)
      else buildGen.withBaseModule(packages, "MavenModule" -> "MavenTests")
        .fold((None, packages))((base, pkgs) => (Some(base), pkgs))
    buildGen.writeBuildFiles(
      baseDir = millWorkspace,
      packages = packages0,
      merge = merge.value,
      baseModule = baseModule,
      millJvmVersion = millJvmId
    )
  }

  private def isBom(dep: Dependency) = dep.getScope == "import" && dep.getType == "pom"

  private def isSpringBootParent(parent: Parent): Boolean =
    parent.getGroupId == SpringBoot.GroupId && parent.getArtifactId == SpringBoot.ParentArtifactId

  private def findSpringBootBom(model: Model): Option[Dependency] =
    Option(model.getDependencyManagement)
      .flatMap(_.getDependencies.asScala.find(dep =>
        dep.getGroupId == SpringBoot.GroupId &&
          (dep.getArtifactId == SpringBoot.DependenciesArtifactId || dep.getArtifactId == SpringBoot.ParentArtifactId) &&
          dep.getScope == "import" &&
          dep.getType == "pom"
      ))

  /**
   * Detect if the project is a Spring Boot project by checking if it inherits from spring-boot-starter-parent
   * or imports spring-boot-dependencies/spring-boot-starter-parent as a BOM in its raw model.
   */
  private def isSpringBootProject(model: Model, rawModel: Model): Boolean =
    Option(model.getParent).exists(isSpringBootParent) ||
      findSpringBootBom(rawModel).isDefined ||
      rawModel.getDependencies.asScala.exists(_.getGroupId == SpringBoot.GroupId)

  private def nonEmpty(value: String): Option[String] = Option(value).filter(_.nonEmpty)

  private def collectDependencyManagement(
      dm: DependencyManagement,
      toMvnOrModuleDep: Dependency => Either[MvnDep, ModuleDep],
      moduleDepLookup: PartialFunction[Dependency, ModuleDep]
  ): (Seq[MvnDep], Seq[MvnDep], Seq[ModuleDep]) = {
    val (bomDeps, deps) = dm.getDependencies.asScala.toSeq.partition(isBom)
    val (bomMvnDeps, bomModuleDeps) = bomDeps.partitionMap(toMvnOrModuleDep)
    val depManagement = deps.collect {
      case dep if !moduleDepLookup.isDefinedAt(dep) => toMvnDep(dep)
    }
    (bomMvnDeps, depManagement, bomModuleDeps)
  }

  private val PropertyRegex = """\$\{([^}]+)}""".r

  /**
   * Detect Spring Boot platform version from spring-boot-starter-parent or imported BOM
   * (resolving property version from effective properties).
   */
  private def detectSpringBootVersion(model: Model, rawModel: Model): Option[String] = {
    val parentVersion = Option(model.getParent)
      .filter(isSpringBootParent)
      .flatMap(parent => nonEmpty(parent.getVersion))

    val fromBom = findSpringBootBom(rawModel)
      .flatMap(dep => nonEmpty(dep.getVersion))
      .map {
        case PropertyRegex(propName) =>
          Option(model.getProperties.getProperty(propName)).getOrElse(s"$${$propName}")
        case other => other
      }

    parentVersion.orElse(fromBom).orElse {
      val springBootVersions = model.getDependencies.asScala
        .filter(_.getGroupId == SpringBoot.GroupId)
        .flatMap(dep => nonEmpty(dep.getVersion))
      springBootVersions
        .groupBy(identity)
        .map { case (k, v) => (k, v.size) }
        .toSeq
        .sortBy(-_._2)
        .headOption
        .map(_._1)
    }
  }

  private val QuarkusPluginArtifactId = "quarkus-maven-plugin"

  private def detectQuarkusPluginVersion(model: Model): Option[String] = {
    model.getBuild.getPlugins.asScala.find(p =>
      p.getArtifactId == QuarkusPluginArtifactId
    ).flatMap(p => nonEmpty(p.getVersion))
  }

  private def isFrameworkBomSource(sourceId: String): Boolean = {
    sourceId.split(":") match {
      case Array(groupId, artifactId, _*) => {
        (groupId == SpringBoot.GroupId && (artifactId == SpringBoot.DependenciesArtifactId || artifactId == SpringBoot.ParentArtifactId)) ||
        (groupId == Micronaut.PlatformGroupId &&
          (artifactId == Micronaut.PlatformArtifactId || Micronaut.BomArtifactIds.contains(
            artifactId
          )))
      }
      case _ => false
    }
  }

  private def filterFrameworkBomDeps(dm: DependencyManagement): DependencyManagement = {
    val filteredDeps = dm.getDependencies.asScala.filterNot { dep =>
      val location = dep.getLocation("")
      val source = if (location != null) location.getSource else null
      val sourceId = if (source != null) Option(source.getModelId).getOrElse("") else ""
      isFrameworkBomSource(sourceId)
    }
    val filteredDm = new DependencyManagement()
    filteredDm.setDependencies(filteredDeps.asJava)
    filteredDm
  }

  private def toMvnDep(dep: Dependency) = {
    import dep.*
    MvnDep(
      organization = getGroupId,
      name = getArtifactId,
      version = Option(getVersion).getOrElse(""),
      // Sanitize unresolved properties such as ${os.detected.name} to prevent interpolation.
      classifier = Option(getClassifier).map(_.replaceAll("[$]", "")),
      `type` = getType match {
        case null | "jar" | "pom" => None
        case tpe => Some(tpe)
      },
      excludes = getExclusions.asScala.map(x => (x.getGroupId, x.getArtifactId)).toSeq
    )
  }

  private def toPomParentProject(parent: Parent) = {
    if (parent == null) None
    else {
      import parent.*
      Some(Artifact(getGroupId, getArtifactId, getVersion))
    }
  }

  private def toPomSettings(model: Model) = {
    import model.*
    PomSettings(
      description = Option(getDescription).getOrElse(""),
      organization = Option(getGroupId).getOrElse(""),
      url = Option(getUrl).getOrElse(""),
      licenses = getLicenses.asScala.map(toLicense).toSeq,
      versionControl = toVersionControl(getScm),
      developers = getDevelopers.asScala.map(toDeveloper).toSeq
    )
  }

  private def toLicense(license: MvnLicense) = {
    import license.*
    License(
      name = Option(getName).getOrElse(""),
      url = Option(getUrl).getOrElse(""),
      distribution = Option(getDistribution).getOrElse("")
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
      id = Option(getId).getOrElse(""),
      name = Option(getName).getOrElse(""),
      url = Option(getUrl).getOrElse(""),
      organization = Option(getOrganization),
      organizationUrl = Option(getOrganizationUrl)
    )
  }

  private def normalizeBuild(packages: Seq[PackageSpec]) = {
    val moduleLookup = packages.flatMap(_.modulesBySegments).toMap
      .compose[ModuleDep](dep => dep.segments ++ dep.childSegment)

    def recMvnDeps(module: ModuleSpec): Seq[MvnDep] = module.mvnDeps.base ++
      module.moduleDeps.base.flatMap(dep => recMvnDeps(moduleLookup(dep)))

    packages.map(pkg =>
      pkg.copy(module = pkg.module.recMap { module =>
        var module0 = module
        if (module0.isPublishModule) {
          val (managedBomModules, bomModuleDeps) = module0.bomModuleDeps.base.partitionMap { dep =>
            val bomModule = moduleLookup(dep)
            Either.cond(bomModule.isBomModule && bomModule.isPublishModule, dep, bomModule)
          }
          if (managedBomModules.nonEmpty) {
            module0 = module0.copy(
              bomMvnDeps = module0.bomMvnDeps.copy(base =
                module0.bomMvnDeps.base ++ managedBomModules.flatMap(_.bomMvnDeps.base)
              ),
              depManagement = module0.depManagement.copy(base =
                module0.depManagement.base ++ managedBomModules.flatMap(_.depManagement.base)
              ),
              bomModuleDeps = bomModuleDeps
            )
          }
        }
        if (module0.testFramework.base.contains("")) {
          val testMixin = ModuleSpec.testModuleMixin(recMvnDeps(module0))
          if (testMixin.nonEmpty) {
            module0 =
              module0.copy(supertypes = module0.supertypes ++ testMixin, testFramework = None)
          }
        }
        module0
      })
    )
  }
}
