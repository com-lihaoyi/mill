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
      @mainargs.arg(doc =
        "Coursier JVM identifier to assign to mill-jvm-version key in the build header"
      )
      millJvmId: String = "system",
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag
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

      if (
        model.getPackaging == "pom" &&
        !os.exists(moduleDir / "src") &&
        Option(model.getDependencyManagement).exists(!_.getDependencies.isEmpty)
      ) {
        val (boms, deps) =
          model.getDependencyManagement.getDependencies.asScala.toSeq.partition(isBom)
        val (bomMvnDeps, bomModuleDeps) =
          boms.partitionMap(dep => moduleDepLookup.lift(dep).toRight(toMvnDep(dep)))
        val (depManagement, moduleDeps) =
          deps.partitionMap(dep => moduleDepLookup.lift(dep).toRight(toMvnDep(dep)))
        mainModule = mainModule.copy(
          imports = "import mill.javalib.*" +: mainModule.imports,
          supertypes = "JavaModule" +: "BomModule" +: mainModule.supertypes,
          bomMvnDeps = bomMvnDeps,
          depManagement = depManagement,
          moduleDeps = moduleDeps,
          bomModuleDeps = bomModuleDeps
        )
      } else {
        val (modules, deps) =
          model.getDependencies.asScala.toSeq.partition(moduleDepLookup.isDefinedAt)
        def mvnDeps(scope: String) = deps.collect {
          case dep if dep.getScope == scope => toMvnDep(dep)
        }
        def moduleDeps(scope: String) = modules.collect {
          case dep if dep.getScope == scope => moduleDepLookup(dep)
        }
        val (bomMvnDeps, depManagement, bomModuleDeps) =
          Option(model.getDependencyManagement).fold((Nil, Nil, Nil)) { dm =>
            val (boms, deps) = dm.getDependencies.asScala.toSeq.partition(isBom)
            val (bomMvnDeps, bomModuleDeps) =
              boms.partitionMap(dep => moduleDepLookup.lift(dep).toRight(toMvnDep(dep)))
            val depManagement = deps.map(toMvnDep)
            (bomMvnDeps, depManagement, bomModuleDeps)
          }
        val (
          mainSourcesFolders,
          mainSources,
          mainResources,
          testSourcesFolders,
          testSources,
          testResources
        ) = plugins.sources
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
          sourcesFolders = mainSourcesFolders,
          sources = mainSources,
          resources = mainResources,
          artifactName = Option(model.getArtifactId)
        ).withErrorProneModule(plugins.errorProneMvnDeps)
        if (os.exists(moduleDir / "src/test")) {
          val testMvnDeps = mvnDeps("test")
          ModuleSpec.testModuleMixin(testMvnDeps).foreach { mixin =>
            val testModuleDeps = modules.collect {
              case dep if dep.getScope == "test" =>
                moduleDepLookup(dep).copy(childSegment =
                  Option.when(dep.getType == "test-jar")("test")
                )
            }
            var testModule = ModuleSpec(
              name = "test",
              supertypes = Seq("MavenTests"),
              mixins = Seq(mixin),
              forkArgs = plugins.testForkArgs,
              forkWorkingDir = Some(os.rel),
              mvnDeps = testMvnDeps,
              compileMvnDeps = mainModule.compileMvnDeps.base,
              runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
              moduleDeps = Values(testModuleDeps, appendSuper = true),
              compileModuleDeps = mainModule.compileModuleDeps.base,
              runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
              sourcesFolders = testSourcesFolders,
              sources = testSources,
              resources = testResources,
              testParallelism = Some(false),
              testSandboxWorkingDir = Some(false)
            )
            if (mixin == "TestModule.Junit5") {
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
            mainModule = mainModule.copy(test = Some(testModule))
          }
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
    packages = adjustModuleDeps(packages)

    val (depNames, packages0) =
      if (noMeta.value) (Nil, packages) else BuildGen.withNamedDeps(packages)
    val (baseModule, packages1) =
      Option.when(!noMeta.value)(BuildGen.withBaseModule(packages0, "MavenTests", "MavenModule"))
        .flatten.fold((None, packages0))((base, packages) => (Some(base), packages))
    BuildGen.writeBuildFiles(packages1, millJvmId, merge.value, depNames, baseModule)
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

  private def adjustModuleDeps(packages: Seq[PackageSpec]) = {
    val moduleLookup =
      packages.map(pkg => (pkg.dir.segments, pkg.module)).toMap[Seq[String], ModuleSpec]
    def adjust(module: ModuleSpec) = {
      var module0 = module
      if (module.supertypes.contains("PublishModule")) {
        val (bomModuleDeps, bomModuleDepRefs) = module0.bomModuleDeps.base.partition { dep =>
          val module = moduleLookup(dep.segments)
          module.supertypes.contains("BomModule") && module.supertypes.contains("PublishModule")
        }
        if (bomModuleDepRefs.nonEmpty) {
          module0 = module0.copy(
            bomMvnDeps = module0.bomMvnDeps.copy(appendRefs = bomModuleDepRefs),
            depManagement = module0.depManagement.copy(appendRefs = bomModuleDepRefs),
            bomModuleDeps = bomModuleDeps
          )
        }
      }
      module0
    }
    packages.map(pkg => pkg.copy(module = adjust(pkg.module)))
  }
}
