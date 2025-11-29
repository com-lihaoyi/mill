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
      noMeta: mainargs.Flag
  ): Unit = {
    println("converting Maven build")

    val modelBuildingResults = Modeler().buildAll()
    val moduleDepLookup: PartialFunction[Dependency, ModuleDep] =
      modelBuildingResults.map { result =>
        val model = result.getEffectiveModel
        val key = (model.getGroupId, model.getArtifactId, model.getVersion)
        val moduleDep = ModuleDep(os.Path(model.getProjectDirectory).subRelativeTo(os.pwd))
        key -> moduleDep
      }.toMap.compose {
        case dep: Dependency => (dep.getGroupId, dep.getArtifactId, dep.getVersion)
      }

    val packages = modelBuildingResults.map { result =>
      val model = result.getEffectiveModel
      val moduleDir = os.Path(model.getProjectDirectory)
      val plugins = Plugins(model)

      var mainModule = ModuleSpec(
        name = moduleDir.last,
        repositories = model.getRepositories.iterator.asScala.collect {
          case repo if repo.getId != "central" => repo.getUrl
        }.toSeq
      )
      model.getPackaging match {
        case "pom" if model.getModules.isEmpty && !os.exists(moduleDir / "src") =>
          val (bomMvnDeps, depManagement, moduleDeps, bomModuleDeps) =
            Option(model.getDependencyManagement).fold((Nil, Nil, Nil, Nil)) { dm =>
              val (boms, deps) = dm.getDependencies.iterator.asScala.toSeq.partition(isBom)
              val (bomMvnDeps, bomModuleDeps) =
                boms.partitionMap(dep => moduleDepLookup.lift(dep).toRight(toMvnDep(dep)))
              val (depManagement, moduleDeps) =
                deps.partitionMap(dep => moduleDepLookup.lift(dep).toRight(toMvnDep(dep)))
              (bomMvnDeps, depManagement, moduleDeps, bomModuleDeps)
            }
          mainModule = mainModule.copy(
            imports = "import mill.javalib.*" +: mainModule.imports,
            supertypes = "JavaModule" +: "BomModule" +: mainModule.supertypes,
            bomMvnDeps = bomMvnDeps,
            depManagement = depManagement,
            moduleDeps = moduleDeps,
            bomModuleDeps = bomModuleDeps
          )
        case _ =>
          val (modules, deps) =
            model.getDependencies.iterator.asScala.toSeq.partition(moduleDepLookup.isDefinedAt)
          def mvnDeps(scope: String) = deps.collect {
            case dep if dep.getScope == scope => toMvnDep(dep)
          }
          def moduleDeps(scope: String) = modules.collect {
            case dep if dep.getScope == scope => moduleDepLookup(dep)
          }
          val (bomMvnDeps, bomModuleDeps) =
            Option(model.getDependencyManagement).fold((Nil, Nil)) { dm =>
              val boms = dm.getDependencies.iterator.asScala.filter(isBom).toSeq
              boms.partitionMap(dep => moduleDepLookup.lift(dep).toRight(toMvnDep(dep)))
            }

          mainModule = mainModule.copy(
            imports = "import mill.javalib.*" +: mainModule.imports,
            supertypes = "MavenModule" +: mainModule.supertypes,
            mvnDeps = mvnDeps("compile"),
            compileMvnDeps = mvnDeps("provided"),
            runMvnDeps = mvnDeps("runtime"),
            bomMvnDeps = bomMvnDeps,
            moduleDeps = moduleDeps("compile"),
            compileModuleDeps = moduleDeps("provided"),
            runModuleDeps = moduleDeps("runtime"),
            bomModuleDeps = bomModuleDeps,
            javacOptions = plugins.javacOptions,
            artifactName = Option(model.getArtifactId)
          )

          val errorProneDeps = plugins.errorProneMvnDeps
          if (errorProneDeps.nonEmpty) {
            mainModule = mainModule.withErrorProneModule(errorProneDeps)
          }

          if (os.exists(moduleDir / "src/test")) {
            val testMvnDeps = mvnDeps("test")
            ModuleSpec.testModuleMixin(testMvnDeps).foreach { mixin =>
              val testModuleDeps = modules.collect {
                case dep if dep.getScope == "test" =>
                  moduleDepLookup(dep).copy(nestedModule =
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
                compileMvnDeps = mainModule.compileMvnDeps,
                runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
                moduleDeps = Values(extend = true, testModuleDeps),
                compileModuleDeps = mainModule.compileModuleDeps,
                runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
                testParallelism = Some(false),
                testSandboxWorkingDir = Some(false)
              )
              mixin match {
                // Maven can resolve junit-platform-launcher version using junit-bom transitive
                // dependency. Since Coursier cannot do the same, make the dependency explicit.
                case "TestModule.Junit5"
                    if testModule.runMvnDeps.base.exists(dep =>
                      dep.name == "junit-platform-launcher" && dep.organization == "org.junit.platform" && dep.version.isEmpty
                    ) && !mainModule.depManagement.base.exists(dep =>
                      dep.name == "junit-platform-launcher" && dep.organization == "org.junit.platform"
                    ) && !mainModule.bomMvnDeps.base.exists(dep =>
                      dep.name == "junit-bom" && dep.organization == "org.junit"
                    ) =>
                  testModule.mvnDeps.base.collectFirst {
                    case dep if dep.organization == "org.junit.jupiter" && dep.version.nonEmpty =>
                      dep.version
                  }.foreach { junitVersion =>
                    val junitBom = MvnDep("org.junit", "junit-bom", junitVersion)
                    testModule = testModule.copy(bomMvnDeps = Values(extend = true, Seq(junitBom)))
                  }
                case _ =>
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

    val (depRefs, packages0) =
      if (noMeta.value) (Nil, packages) else BuildGen.withNamedDeps(packages)
    val (baseModule, packages1) =
      Option.when(!noMeta.value)(BuildGen.withBaseModule(packages0, "MavenTests", "MavenModule"))
        .flatten.fold((None, packages0))((base, packages) => (Some(base), packages))
    BuildGen.writeBuildFiles(packages1, merge.value, depRefs, baseModule)
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
      distribution = getDistribution
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
}
