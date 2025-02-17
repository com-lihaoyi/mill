package mill.main.sbt

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.*
import os.{Path, SubPath}

import scala.jdk.CollectionConverters.*
import ba.sake.sbt.build.extract.*
import mill.runner.FileImportGraph

@mill.api.internal
object SbtBuildGenMain extends BuildGenBase[ProjectExport] {
  type C = SbtBuildGenMain.Config

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[Config].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: Config): Unit = {
    println("converting Sbt build")
    val workspace = os.pwd
    val sbtCmd = if (scala.util.Properties.isWin) "sbt.bat" else "sbt"
    os.call((sbtCmd, "exportBuildStructure"), cwd = workspace, stdout = os.Inherit)
    val allProjectsMap = os.list(workspace / "target/build-export").filter(_.ext == "json").map { file =>
      val json = os.read(file)
      val projExport = upickle.default.read[ProjectExport](json)
      os.Path(projExport.base) -> projExport
    }.toMap
    
    val rootProject = allProjectsMap(workspace)
    val subProjectsMap = allProjectsMap - workspace
    
    val input = Tree(
      Node(Seq.empty, rootProject),
      subProjectsMap.values.view.map { proj => 
        val dir = os.Path(proj.base).subRelativeTo(workspace)
        Tree(Node(dir.segments, proj), Seq.empty)
      }.toSeq
    )

    convertWriteOut(cfg, cfg.shared, input)

    println("converted Sbt build to Mill")
  }

  override def getPackage(model: ProjectExport): (String, String, String) =
    (model.organization, model.artifactName, model.version)

  override def getArtifactId(model: ProjectExport): String = model.artifactName

  
  override def getBaseInfo(
      input: Tree[Node[ProjectExport]],
      cfg: Config,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo = {
    val model = input.node.value
    val pomSettings = extractPomSettings(model)
    val publishVersion = model.version
    val publishProperties = getPublishProperties(model, cfg.shared)

    val typedef = IrTrait(
      cfg.shared.jvmId,
      baseModule,
      getModuleSupertypes,
      model.javacOptions,
      model.scalacOptions,
      pomSettings,
      publishVersion,
      publishProperties,
      getRepositories(model)
    )

    IrBaseInfo(model.javacOptions, getRepositories(model), noPom = false, publishVersion, publishProperties, typedef)
  }

  override def extractIrBuild(
      cfg: Config,
      baseInfo: IrBaseInfo,
      build: Node[ProjectExport],
      packages: Map[(String, String, String), String]
  ): IrBuild = {
    val model = build.value
    val scopedDeps = extractScopedDeps(model, cfg)
    val version = model.version
    val millSourcePath = getMillSourcePath(model)
    IrBuild(
      scopedDeps = scopedDeps,
      testModule = cfg.shared.testModule,
      hasTest = os.exists(millSourcePath / "src/test"),
      dirs = build.dirs,
      repositories = getRepositories(model),
      javacOptions = model.javacOptions,
      scalacOptions = model.scalacOptions,
      scalaVersion = Option(model.scalaVersion),
      projectName = getArtifactId(model),
      pomSettings = if (baseInfo.noPom) extractPomSettings(model) else null,
      publishVersion = if (version == baseInfo.publishVersion) null else version,
      packaging = model.artifactType,
      pomParentArtifact = null,
      resources = model.resourceDirs.filterNot(_.endsWith("resources")).map(os.Path(_).subRelativeTo(millSourcePath)), // filter out default ones (from MavenModule)
      testResources = model.testResourceDirs.filterNot(_.endsWith("resources")).map(os.Path(_).subRelativeTo(millSourcePath)), // filter out default ones
      publishProperties = getPublishProperties(model, cfg.shared).diff(baseInfo.publishProperties)
    )
  }


  override def getSuperTypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[ProjectExport]): Seq[String] = 
    Seq("RootModule") ++
      cfg.shared.baseModule.fold(getModuleSupertypes)(Seq(_))

 
  override def getTestsSuperType: String = "SbtTests"
  
  private def getModuleSupertypes: Seq[String] = Seq("PublishModule", "SbtModule")
  
  private def getMillSourcePath(model: ProjectExport): Path = os.Path(model.base)
  
  private def getRepositories(model: ProjectExport): Seq[String] =
    model.repositories
      .map(repo => s"coursier.maven.MavenRepository(${escape(repo)})")
  
  // TODO ???
  private def getPublishProperties(model: ProjectExport, cfg: BuildGenUtil.Config): Seq[(String, String)] =
    Seq.empty
    /*if (cfg.publishProperties.value) {
      val props = model.pub
      props.stringPropertyNames().iterator().asScala
        .map(key => (key, props.getProperty(key)))
        .toSeq
        .sorted
    } else Seq.empty*/

  private def interpIvy(dep: DependencyExport): String =
    BuildGenUtil.renderIvyString(
      dep.organization,
      dep.name,
      dep.revision,
      dep.extraAttributes.get("type").orNull,
      dep.extraAttributes.get("classifier").orNull,
      dep.excludes.map(x => (x.organization, x.name)),
      isCrossBinary = dep.crossVersion == "binary"
    )

  private def extractPomSettings(model: ProjectExport): IrPom = {
    IrPom(
      model.description,
      model.organization, // Mill uses group for POM org
      model.homepage.orNull,
      licenses = model.licenses
        .map(lic => IrLicense(lic.name, lic.name, lic.url)),
      versionControl = model.scmInfo.map(scm =>
        IrVersionControl(scm.browseUrl, scm.connection, scm.devConnection.orNull, tag = null)
      ).getOrElse(IrVersionControl(null, null, null, null)),
      developers = model.developers.map(dev =>
          IrDeveloper(
            dev.id,
            dev.name,
            dev.url,
            model.organization,
            model.homepage.orNull
          )
        )
    )
  }

  private def extractScopedDeps(
      model: ProjectExport,
      cfg: Config
  ): IrScopedDeps = {
    var sd = IrScopedDeps()

    val hasTest = os.exists(getMillSourcePath(model) / "src/test")
    val ivyDep: DependencyExport => String = {
      cfg.shared.depsObject.fold(interpIvy(_)) { objName => dep =>
        val depName = s"`${dep.organization}:${dep.name}`"
        sd = sd.copy(namedIvyDeps = sd.namedIvyDeps :+ (depName, interpIvy(dep)))
        s"$objName.$depName"
      }
    }

    sd = sd.copy(mainModuleDeps = sd.mainModuleDeps ++ model.interProjectDependencies.map(p => s"build.${FileImportGraph.backtickWrap(p.project)}"))
    // exclude scalalib
    val externalDeps = model.externalDependencies.filterNot { dep =>
      dep.organization == "org.scala-lang" && (dep.name == "scala3-library" || dep.name == "scala-library")
    }
    externalDeps.foreach { dep =>
      val id = (dep.organization, dep.name, dep.revision)
      dep.configurations.getOrElse("compile") match {
        case "compile" =>
          if (isBom(id)) println(s"assuming compile dependency $id is a BOM")
          val ivy = ivyDep(dep)
          sd = sd.copy(mainIvyDeps = sd.mainIvyDeps + ivy)
        case "provided" =>
          val ivy = ivyDep(dep)
          sd = sd.copy(mainCompileIvyDeps = sd.mainCompileIvyDeps + ivy)
        case "runtime" =>
          val ivy = ivyDep(dep)
          sd = sd.copy(mainRunIvyDeps = sd.mainRunIvyDeps + ivy)
        case "test" =>
          val ivy = ivyDep(dep)
          if (isBom(id)) {
            sd = sd.copy(testBomIvyDeps = sd.testBomIvyDeps + ivy)
          } else {
            sd = sd.copy(testIvyDeps = sd.testIvyDeps + ivy)
          }
          if (hasTest && sd.testModule.isEmpty) {
            sd = sd.copy(testModule = testModulesByGroup.get(dep.organization))
          }
        case scope =>
          println(s"ignoring $scope dependency $id")
      }
    }
    sd
  }

  @main
  @mill.api.internal
  case class Config(
      shared: BuildGenUtil.Config
  )

}
