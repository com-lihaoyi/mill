package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.api.daemon.internal.internal
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
@internal
object MavenBuildGenMain extends BuildGenBase.MavenAndGradle {
  override type M = Model
  override type D = Dependency
  override type C = Config

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

  override def getBaseInfo(
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
      cfg.shared.basicConfig.jvmId,
      baseModule,
      getModuleSupertypes,
      javacOptions,
      scalaVersion,
      scalacOptions,
      pomSettings,
      publishVersion,
      publishProperties,
      repositories
    )

    IrBaseInfo(typedef)
  }

  override type ModuleFqnMap = Map[(String, String, String), String]
  override def getModuleFqnMap(moduleNodes: Seq[Node[Model]])
      : Map[(String, String, String), String] =
    buildModuleFqnMap(moduleNodes)(getProjectGav)

  override def extractIrBuild(
      cfg: Config,
      // baseInfo: IrBaseInfo,
      build: Node[Model],
      moduleFqnMap: Map[(String, String, String), String]
  ): IrBuild = {
    val model = build.value
    val scopedDeps = extractScopedDeps(model, moduleFqnMap, cfg)
    val version = model.getVersion
    IrBuild(
      scopedDeps = scopedDeps,
      testModule = cfg.shared.basicConfig.testModule,
      testModuleMainType = "MavenTests",
      hasTest = os.exists(getMillSourcePath(model) / "src/test"),
      dirs = build.dirs,
      repositories = getRepositories(model),
      javacOptions = Plugins.MavenCompilerPlugin.javacOptions(model),
      scalaVersion = None,
      scalacOptions = None,
      projectName = getArtifactId(model),
      pomSettings = extractPomSettings(model),
      publishVersion = version,
      packaging = model.getPackaging,
      pomParentArtifact = mkPomParent(model.getParent),
      resources =
        processResources(model.getBuild.getResources, getMillSourcePath(model))
          .filterNot(_ == mavenMainResourceDir),
      testResources =
        processResources(model.getBuild.getTestResources, getMillSourcePath(model))
          .filterNot(_ == mavenTestResourceDir),
      publishProperties = getPublishProperties(model, cfg.shared),
      jvmId = cfg.shared.basicConfig.jvmId,
      // Maven subproject tests run in the subproject folder, unlike Gradle
      // and SBT whose subproject tests run in the root project folder
      testForkDir = Some("moduleDir")
    )
  }

  def getModuleSupertypes: Seq[String] = Seq("PublishModule", "MavenModule")

  def getProjectGav(model: Model): (String, String, String) =
    (model.getGroupId, model.getArtifactId, model.getVersion)

  override def getArtifactId(model: Model): String = model.getArtifactId

  def getMillSourcePath(model: Model): Path = os.Path(model.getProjectDirectory)

  override def getSupertypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[Model]): Seq[String] =
    cfg.shared.basicConfig.baseModule.fold(getModuleSupertypes)(Seq(_))

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
      .map(repo => escape(repo.getUrl))
      .toSeq
      .sorted

  def getPublishProperties(
      model: Model,
      cfg: BuildGenUtil.MavenAndGradleCommonConfig
  ): Seq[(String, String)] =
    if (cfg.publishProperties.value) {
      val props = model.getProperties
      props.stringPropertyNames().iterator().asScala
        .map(key => (key, props.getProperty(key)))
        .toSeq
        .sorted
    } else Seq.empty

  def interpMvn(dep: Dependency): String =
    BuildGenUtil.renderMvnString(
      dep.getGroupId,
      dep.getArtifactId,
      None,
      dep.getVersion,
      dep.getType,
      dep.getClassifier,
      dep.getExclusions.iterator().asScala.map(x => (x.getGroupId, x.getArtifactId))
    )

  def mkPomParent(parent: Parent): IrArtifact =
    if (parent == null) null
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
    val mvnDep: Dependency => String = {
      cfg.shared.basicConfig.depsObject.fold(interpMvn(_)) { objName => dep =>
        {
          val depName = s"`${dep.getGroupId}:${dep.getArtifactId}`"
          sd = sd.copy(namedMvnDeps = sd.namedMvnDeps :+ (depName, interpMvn(dep)))
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
            val mvn = mvnDep(dep)
            sd = sd.copy(mainMvnDeps = sd.mainMvnDeps + mvn)
          }
        case "provided" =>
          // Provided dependencies are available at compile time in both
          // `src/main/java` and `src/test/java`
          if (packages.isDefinedAt(id))
            sd = sd.copy(
              mainCompileModuleDeps = sd.mainCompileModuleDeps + packages(id),
              testCompileModuleDeps = sd.testCompileModuleDeps + packages(id)
            )
          else {
            val mvn = mvnDep(dep)
            sd = sd.copy(
              mainCompileMvnDeps = sd.mainCompileMvnDeps + mvn,
              testCompileMvnDeps = sd.testCompileMvnDeps + mvn
            )
          }
        case "runtime" =>
          if (packages.isDefinedAt(id))
            sd = sd.copy(mainRunModuleDeps = sd.mainRunModuleDeps + packages(id))
          else {
            val mvn = mvnDep(dep)
            sd = sd.copy(mainRunMvnDeps = sd.mainRunMvnDeps + mvn)
          }

        case "test" =>
          if (packages.isDefinedAt(id))
            sd = sd.copy(testModuleDeps = sd.testModuleDeps + packages(id))
          else {
            val mvn = mvnDep(dep)
            if (isBom(id)) {
              sd = sd.copy(testBomMvnDeps = sd.testBomMvnDeps + mvn)
            } else {
              sd = sd.copy(testMvnDeps = sd.testMvnDeps + mvn)
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
  @internal
  case class Config(
      shared: BuildGenUtil.MavenAndGradleCommonConfig,
      @arg(doc = "use cache for Maven repository system")
      cacheRepository: Flag = Flag(),
      @arg(doc = "process Maven plugin executions and configurations")
      processPlugins: Flag = Flag()
  ) extends ModelerConfig

}
