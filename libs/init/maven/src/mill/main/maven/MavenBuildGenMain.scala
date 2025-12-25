package mill.main.maven

import mill.main.buildgen.*
import mill.main.buildgen.ModuleSpec.*
import org.apache.maven.model.{Developer as MvnDeveloper, License as MvnLicense, *}

import scala.jdk.CollectionConverters.*

object MavenBuildGenMain {

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
      millJvmId: Option[String]
  ): Unit = {
    println("converting Maven build")
    val modelBuildingResults = Modeler().buildAll()
    val moduleDepLookup: PartialFunction[Dependency, ModuleDep] = modelBuildingResults.map { mbr =>
      val model = mbr.getEffectiveModel
      val key = (model.getGroupId, model.getArtifactId, model.getVersion)
      val dep = ModuleDep(os.Path(model.getProjectDirectory).subRelativeTo(os.pwd).segments)
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
          mainModule = mainModule.copy(
            sourcesFolders = Values(empty = os.exists(moduleDir / "src/main/java")),
            resources = Values(empty = os.exists(moduleDir / "src/main/resources"))
          )
          if (Option(model.getDependencyManagement).exists(!_.getDependencies.isEmpty)) {
            val (bomDeps, deps) =
              model.getDependencyManagement.getDependencies.asScala.toSeq.partition(isBom)
            val (bomMvnDeps, bomModuleDeps) = bomDeps.partitionMap(toMvnOrModuleDep)
            val (depManagement, moduleDeps) = deps.partitionMap(toMvnOrModuleDep)
            mainModule = mainModule.copy(
              imports = "import mill.javalib.*" +: mainModule.imports,
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
          val (bomMvnDeps, depManagement, bomModuleDeps) =
            Option(model.getDependencyManagement).fold((Nil, Nil, Nil)) { dm =>
              val (bomDeps, deps) = dm.getDependencies.asScala.toSeq.partition(isBom)
              val (bomMvnDeps, bomModuleDeps) = bomDeps.partitionMap(toMvnOrModuleDep)
              val depManagement = deps.collect {
                case dep if !moduleDepLookup.isDefinedAt(dep) => toMvnDep(dep)
              }
              (bomMvnDeps, depManagement, bomModuleDeps)
            }
          mainModule = mainModule.copy(
            imports = "import mill.javalib.*" +: mainModule.imports,
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
            artifactName = Option(model.getArtifactId)
          ).withErrorProneModule(plugins.errorProneMvnDeps)
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
              supertypes = Seq("MavenTests"),
              mixins = testMixin.toSeq,
              forkArgs = plugins.testForkArgs,
              forkWorkingDir = Some(os.rel),
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
          imports =
            "import mill.javalib.*" +: "import mill.javalib.publish.*" +: mainModule.imports,
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
      PackageSpec(moduleDir.subRelativeTo(os.pwd), mainModule)
    }
    packages = normalizeBuild(packages)

    val (depNames, packages0) =
      if (noMeta.value) (Nil, packages) else BuildGen.withNamedDeps(packages)
    val (baseModule, packages1) = Option.when(!noMeta.value)(BuildGen.withBaseModule(
      packages0,
      Seq("MavenModule"),
      Seq("MavenTests")
    )).flatten.fold((None, packages0))((base, packages) => (Some(base), packages))
    BuildGen.writeBuildFiles(packages1, merge.value, depNames, baseModule, millJvmId)
  }

  private def isBom(dep: Dependency) = dep.getScope == "import" && dep.getType == "pom"

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
    def recMvnDeps(module: ModuleSpec): Seq[MvnDep] = module.mvnDeps.base ++ module.moduleDeps.base
      .flatMap(dep => recMvnDeps(moduleLookup(dep.segments ++ dep.childSegment)))
    packages.map(pkg =>
      pkg.copy(module = pkg.module.recMap { module =>
        var module0 = module
        if (module0.supertypes.contains("PublishModule")) {
          val (bomModuleDeps, bomModuleRefs) = module0.bomModuleDeps.base.partition { dep =>
            val module = moduleLookup(dep.segments ++ dep.childSegment)
            module.supertypes.contains("BomModule") && module.supertypes.contains("PublishModule")
          }
          if (bomModuleRefs.nonEmpty) {
            module0 = module0.copy(
              bomMvnDeps = module0.bomMvnDeps.copy(appendRefs = bomModuleRefs),
              depManagement = module0.depManagement.copy(appendRefs = bomModuleRefs),
              bomModuleDeps = bomModuleDeps
            )
          }
        }
        if (module0.testFramework.base.contains("")) {
          val testMixin = ModuleSpec.testModuleMixin(recMvnDeps(module0))
          if (testMixin.nonEmpty) {
            module0 = module0.copy(mixins = module0.mixins ++ testMixin, testFramework = None)
          }
        }
        module0
      })
    )
  }
}
