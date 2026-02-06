package mill.main.maven

import mill.main.buildgen.*
import mill.main.buildgen.ModuleSpec.*
import mill.main.maven.MavenUtil.*
import org.apache.maven.model.{Developer as _, License as _, *}

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
      val plugins = Plugins(model, mvnWorkspace)
      var mainModule = ModuleSpec(
        name = moduleDir.last,
        repositories = model.getRepositories.asScala.collect {
          case repo if repo.getId != "central" => repo.getUrl
        }.toSeq
      )

      model.getPackaging match {
        case "pom" =>
          if (Option(model.getDependencyManagement).exists(!_.getDependencies.isEmpty)) {
            val (bomDeps, deps) =
              model.getDependencyManagement.getDependencies.asScala.toSeq.partition(isBom)
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
            artifactName = Option(model.getArtifactId)
          ).withErrorProneModule(plugins.errorProneMvnDeps)
          plugins.withCheckstyleModule(mainModule).foreach(mainModule = _)

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
              forkArgs = plugins.testForkArgs,
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
          pomParentProject = Option(model.getParent).map { parent =>
            import parent.*
            Artifact(getGroupId, getArtifactId, getVersion)
          },
          pomSettings = {
            // Use raw model since the effective one returns derived values for URL fields.
            val model = result.getRawModel
            import model.*
            Some(PomSettings(
              description = Option(getDescription).getOrElse(""),
              organization = Option(getGroupId).getOrElse(""),
              url = Option(getUrl).getOrElse(""),
              licenses = getLicenses.asScala.map { license =>
                import license.*
                License(
                  name = Option(getName).getOrElse(""),
                  url = Option(getUrl).getOrElse(""),
                  distribution = Option(getDistribution).getOrElse("")
                )
              }.toSeq,
              versionControl = Option(getScm).fold(VersionControl()) { scm =>
                import scm.*
                VersionControl(
                  browsableRepository = Option(getUrl),
                  connection = Option(getConnection),
                  developerConnection = Option(getDeveloperConnection),
                  tag = Option(getTag)
                )
              },
              developers = getDevelopers.asScala.map { developer =>
                import developer.*
                Developer(
                  id = Option(getId).getOrElse(""),
                  name = Option(getName).getOrElse(""),
                  url = Option(getUrl).getOrElse(""),
                  organization = Option(getOrganization),
                  organizationUrl = Option(getOrganizationUrl)
                )
              }.toSeq
            ))
          },
          publishVersion = Option(model.getVersion),
          publishProperties =
            if (publishProperties.value) model.getProperties.asScala.toSeq else Nil
        )
      }
      PackageSpec(moduleDir.subRelativeTo(mvnWorkspace), mainModule)
    }
    packages = normalizePackages(packages)

    val build = BuildSpec(packages)
    if (!noMeta.value) {
      if (!declarative) {
        build.deriveDepNames()
      }
      build.deriveBaseModule("MavenModule" -> "MavenTests")
    }
    build.writeFiles(
      declarative = declarative,
      merge = merge.value,
      workspace = millWorkspace,
      millJvmVersion = millJvmId
    )
  }

  private def normalizePackages(packages: Seq[PackageSpec]) = {
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
            // Replace references to managed BOM modules
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
          // Search recursive mvnDeps for supported test module
          for (testMixin <- ModuleSpec.testModuleMixin(recMvnDeps(module0))) {
            module0 =
              module0.copy(supertypes = module0.supertypes :+ testMixin, testFramework = None)
          }
        }
        module0
      })
    )
  }
}
