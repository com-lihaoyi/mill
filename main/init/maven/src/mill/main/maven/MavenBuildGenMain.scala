package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.*
import org.apache.maven.model.{Dependency, Model, Parent}
import os.{Path, SubPath}

import scala.jdk.CollectionConverters.*

/**
 * Converts a Maven build to Mill by generating Mill build file(s) from POM file(s).
 *
 * The generated output should be considered scaffolding and will likely require edits to complete conversion.
 *
 * ===Capabilities===
 * The conversion
 *  - handles deeply nested modules
 *  - captures project settings
 *  - configures dependencies for scopes:
 *    - compile
 *    - provided
 *    - runtime
 *    - test
 *  - configures testing frameworks:
 *    - JUnit 4
 *    - JUnit 5
 *    - TestNG
 *  - configures multiple, compile and test, resource directories
 *
 * ===Limitations===
 * The conversion does not support:
 *  - plugins, other than maven-compiler-plugin
 *  - packaging, other than jar, pom
 *  - build extensions
 *  - build profiles
 */
@mill.api.internal
object MavenBuildGenMain extends BuildGenBase.MavenAndGradle[Model, Dependency] {
  type C = Config

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[Config].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: Config): Unit = {
    val workspace = os.pwd

    println("converting Maven build")
    val modeler = Modeler(cfg)
    val input = Tree.from(Seq.empty[String]) { dirs =>
      val model = modeler(workspace / dirs)
      (Node(dirs, model), model.getModules.iterator().asScala.map(dirs :+ _))
    }

    convertWriteOut(cfg, cfg.shared.basicConfig, input)

    println("converted Maven build to Mill")
  }

  extension (om: Model) override def toOption(): Option[Model] = Some(om)

  def getBaseInfo(
      input: Tree[Node[Model]],
      cfg: Config,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo = {
    val model = input.node.value
    val javacOptions = Plugins.MavenCompilerPlugin.javacOptions(model)
    val scalaVersion = None
    val scalacOptions = None
    val repositories = getRepositories(model)
    val pomSettings = extractPomSettings(model)
    val publishVersion = model.getVersion
    val publishProperties = getPublishProperties(model, cfg.shared)

    val typedef = IrTrait(
      cfg.shared.jvmId,
      baseModule,
      getModuleSupertypes(cfg),
      javacOptions,
      scalaVersion,
      scalacOptions,
      pomSettings,
      publishVersion,
      publishProperties,
      getRepositories(model)
    )

    IrBaseInfo(
      javacOptions,
      scalaVersion,
      scalacOptions,
      repositories,
      noPom = false,
      publishVersion,
      publishProperties,
      typedef
    )
  }

  override def extractIrBuild(
      cfg: Config,
      baseInfo: IrBaseInfo,
      build: Node[Model],
      packages: Map[(String, String, String), String]
  ): IrBuild = {
    val model = build.value
    val scopedDeps = extractScopedDeps(model, packages, cfg)
    val version = model.getVersion
    IrBuild(
      scopedDeps = scopedDeps,
      testModule = cfg.shared.basicConfig.testModule,
      testModuleMainType = "MavenTests",
      hasTest = os.exists(getMillSourcePath(model) / "src/test"),
      dirs = build.dirs,
      repositories = getRepositories(model),
      javacOptions = Plugins.MavenCompilerPlugin.javacOptions(model).diff(baseInfo.javacOptions),
      scalaVersion = None,
      scalacOptions = None,
      projectName = getArtifactId(model),
      pomSettings = if (baseInfo.noPom) extractPomSettings(model) else null,
      publishVersion = if (version == baseInfo.publishVersion) null else version,
      packaging = model.getPackaging,
      pomParentArtifact = mkPomParent(model.getParent),
      resources =
        processResources(model.getBuild.getResources, getMillSourcePath(model))
          .filterNot(_ == mavenMainResourceDir),
      testResources =
        processResources(model.getBuild.getTestResources, getMillSourcePath(model))
          .filterNot(_ == mavenTestResourceDir),
      publishProperties = getPublishProperties(model, cfg.shared).diff(baseInfo.publishProperties)
    )
  }

  override def extraImports: Seq[String] = Seq.empty

  def getModuleSupertypes(cfg: Config): Seq[String] = Seq("PublishModule", "MavenModule")

  def getPackage(model: Model): (String, String, String) = {
    (model.getGroupId, model.getArtifactId, model.getVersion)
  }

  def getArtifactId(model: Model): String = model.getArtifactId

  def getMillSourcePath(model: Model): Path = os.Path(model.getProjectDirectory)

  def getSupertypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[Model]): Seq[String] =
    Seq("RootModule") ++
      cfg.shared.basicConfig.baseModule.fold(getModuleSupertypes(cfg))(Seq(_))

  def processResources(
      input: java.util.List[org.apache.maven.model.Resource],
      millSourcePath: os.Path
  ): Seq[SubPath] = {
    input
      .asScala
      .map(_.getDirectory)
      .map(os.Path(_).subRelativeTo(millSourcePath))
      .toSeq
  }

  def getRepositories(model: Model): Seq[String] =
    model.getRepositories.iterator().asScala
      .filterNot(_.getId == "central")
      .map(repo => s"coursier.maven.MavenRepository(${escape(repo.getUrl)})")
      .toSeq
      .sorted

  def getPublishProperties(model: Model, cfg: BuildGenUtil.Config): Seq[(String, String)] =
    if (cfg.publishProperties.value) {
      val props = model.getProperties
      props.stringPropertyNames().iterator().asScala
        .map(key => (key, props.getProperty(key)))
        .toSeq
        .sorted
    } else Seq.empty

  def interpIvy(dep: Dependency): String =
    BuildGenUtil.renderIvyString(
      dep.getGroupId,
      dep.getArtifactId,
      dep.getVersion,
      dep.getType,
      dep.getClassifier,
      dep.getExclusions.iterator().asScala.map(x => (x.getGroupId, x.getArtifactId))
    )

  def mkPomParent(parent: Parent): IrArtifact =
    if (null == parent) null
    else IrArtifact(parent.getGroupId, parent.getArtifactId, parent.getVersion)

  def extractPomSettings(model: Model): IrPom = {
    IrPom(
      model.getDescription,
      model.getGroupId, // Mill uses group for POM org
      model.getUrl,
      licenses = model.getLicenses.asScala.toSeq
        .map(lic => IrLicense(lic.getName, lic.getName, lic.getUrl)),
      versionControl = Option(model.getScm).fold(IrVersionControl(null, null, null, null))(scm =>
        IrVersionControl(scm.getUrl, scm.getConnection, scm.getDeveloperConnection, scm.getTag)
      ),
      developers = model.getDevelopers.iterator().asScala.toSeq
        .map(dev =>
          IrDeveloper(
            dev.getId,
            dev.getName,
            dev.getUrl,
            dev.getOrganization,
            dev.getOrganizationUrl
          )
        )
    )
  }

  def extractScopedDeps(
      model: Model,
      packages: PartialFunction[(String, String, String), String],
      cfg: Config
  ): IrScopedDeps = {
    var sd = IrScopedDeps()

    val hasTest = os.exists(os.Path(model.getProjectDirectory) / "src/test")
    val ivyDep: Dependency => String = {
      cfg.shared.basicConfig.depsObject.fold(interpIvy(_)) { objName => dep =>
        {
          val depName = s"`${dep.getGroupId}:${dep.getArtifactId}`"
          sd = sd.copy(namedIvyDeps = sd.namedIvyDeps :+ (depName, interpIvy(dep)))
          s"$objName.$depName"
        }
      }
    }

    model.getDependencies.forEach { dep =>
      val id = (dep.getGroupId, dep.getArtifactId, dep.getVersion)
      dep.getScope match {

        case "compile" =>
          if (packages.isDefinedAt(id))
            sd = sd.copy(mainModuleDeps = sd.mainModuleDeps + packages(id))
          else {
            if (isBom(id)) println(s"assuming compile dependency $id is a BOM")
            val ivy = ivyDep(dep)
            sd = sd.copy(mainIvyDeps = sd.mainIvyDeps + ivy)
          }
        case "provided" =>
          if (packages.isDefinedAt(id))
            sd = sd.copy(mainCompileModuleDeps = sd.mainCompileModuleDeps + packages(id))
          else {
            val ivy = ivyDep(dep)
            sd = sd.copy(mainCompileIvyDeps = sd.mainCompileIvyDeps + ivy)
          }
        case "runtime" =>
          if (packages.isDefinedAt(id))
            sd = sd.copy(mainRunModuleDeps = sd.mainRunModuleDeps + packages(id))
          else {
            val ivy = ivyDep(dep)
            sd = sd.copy(mainRunIvyDeps = sd.mainRunIvyDeps + ivy)
          }

        case "test" =>
          if (packages.isDefinedAt(id))
            sd = sd.copy(testModuleDeps = sd.testModuleDeps + packages(id))
          else {
            val ivy = ivyDep(dep)
            if (isBom(id)) {
              sd = sd.copy(testBomIvyDeps = sd.testBomIvyDeps + ivy)
            } else {
              sd = sd.copy(testIvyDeps = sd.testIvyDeps + ivy)
            }
          }

        case scope =>
          println(s"ignoring $scope dependency $id")

      }
      if (hasTest && sd.testModule.isEmpty) {
        sd = sd.copy(testModule = testModulesByGroup.get(dep.getGroupId))
      }
    }
    sd
  }

  @main
  @mill.api.internal
  case class Config(
      shared: BuildGenUtil.Config,
      @arg(doc = "use cache for Maven repository system")
      cacheRepository: Flag = Flag(),
      @arg(doc = "process Maven plugin executions and configurations")
      processPlugins: Flag = Flag()
  ) extends ModelerConfig

}
