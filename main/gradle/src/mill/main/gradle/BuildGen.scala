package mill.main.gradle

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.BuildObject.Companions
import mill.main.buildgen.{BuildGenUtil, BuildObject, Node, Tree}
import mill.runner.FileImportGraph.backtickWrap
import mill.util.Jvm
import org.gradle.api.plugins.JavaPlugin
import org.gradle.tooling.GradleConnector

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.jdk.CollectionConverters.*

/**
 * Converts a Gradle build to Mill by generating Mill build file(s).
 *
 * The generated output should be considered scaffolding and will likely require edits to complete conversion.
 *
 * ===Capabilities===
 * The conversion
 *  - handles deeply nested modules
 *  - configures dependencies for configurations:
 *    - implementation / api
 *    - compileOnly / compileOnlyApi
 *    - runtimeOnly
 *    - testImplementation
 *    - testCompileOnly
 *  - configures testing frameworks:
 *    - JUnit 4
 *    - JUnit 5
 *    - TestNG
 *
 * ===Limitations===
 * The conversion does not support
 *  - custom dependency configurations
 *  - custom tasks
 *  - non-Java sources
 *
 * @note The Gradle API used by the Gradle daemon is tied to the version of Gradle used in the target project (and not the API version used in this module).
 *       Consequently, some features may not be available for projects that use a legacy Gradle version.
 *       For example, the `getSourceSets` method in [[org.gradle.api.plugins.JavaPluginExtension]] was added in Gradle `7.1`.
 */
@mill.api.internal
object BuildGen {

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[BuildGenConfig].constructOrExit(args.toSeq)
    run(cfg)
  }

  private type GradleNode = Node[ProjectModel]

  private def run(cfg: BuildGenConfig): Unit = {
    val workspace = os.pwd

    println("converting Gradle build")
    val connector = GradleConnector.newConnector()

    val args = Seq.newBuilder[String]
      .++=(cfg.jvmId.map { id =>
        println(s"resolving Java home for jvmId $id")
        val home = Jvm.resolveJavaHome(id).getOrThrow
        s"-Dorg.gradle.java.home=$home"
      })
      .+=("--init-script").+=(init.toString())
      .result()

    try {
      println("connecting to Gradle daemon")
      val connection = connector.forProjectDirectory(workspace.toIO).connect()
      try {
        val root = connection.model(classOf[ProjectTree])
          .withArguments(args.asJava)
          .get
        val input = Tree.from(root) { tree =>
          val project = tree.project()
          val dirs = os.Path(project.directory()).subRelativeTo(workspace).segments
          val children = tree.children().asScala.sortBy(_.project().name()).iterator
          (Node(dirs, project), children)
        }

        val output = convert(input, cfg)
        write(if (cfg.merge.value) compact(output) else output)

        println("converted Gradle build to Mill")
      } finally connection.close()
    } finally connector.disconnect()
  }

  private def init: os.Path = {
    val file = os.temp.dir() / "init.gradle"
    val classpath = escape( // for paths on Windows
      os.Path(
        classOf[ProjectTreePlugin].getProtectionDomain.getCodeSource.getLocation.toURI
      ).toString()
    )
    val plugin = classOf[ProjectTreePlugin].getName
    val contents =
      s"""initscript {
         |    dependencies {
         |        classpath files("$classpath")
         |    }
         |}
         |
         |allprojects {
         |    apply plugin: $plugin
         |}
         |""".stripMargin
    os.write(file, contents)
    file
  }

  private def convert(input: Tree[GradleNode], cfg: BuildGenConfig): BuildTree = {
    val packages = // for resolving moduleDeps
      buildPackages(input)(project => (project.group(), project.name(), project.version()))
    val isMonorepo = packages.size > 1

    val (baseJavacOptions, baseReps, basePomSettings, basePublishVersion, baseModuleTypedef) =
      cfg.baseModule match {
        case Some(baseModule) =>
          val project = {
            val projects = input.nodes(Tree.Traversal.BreadthFirst).map(_.module).toSeq
            cfg.baseProject
              .flatMap(name => projects.collectFirst { case m if name == m.name => m })
              .orElse(projects.collectFirst { case m if null != m.maven().pom() => m })
              .orElse(projects.collectFirst { case m if !m.maven().reps().isEmpty => m })
              .getOrElse(input.node.module)
          }
          if (isMonorepo) {
            println(s"settings from ${project.name()} will be shared in base module")
          }
          val supertypes = {
            val b = Seq.newBuilder[String]
            b += "MavenModule"
            if (null != project.maven().pom()) b += "PublishModule"
            b.result()
          }
          val javacOptions = getJavacOptions(project)
          val reps = getRepositories(project)
          val pomSettings = mkPomSettings(project)
          val publishVersion = getPublishVersion(project)

          val zincWorker = cfg.jvmId.fold("") { jvmId =>
            val name = s"${baseModule}ZincWorker"
            val setting = setZincWorker(name)
            val typedef = mkZincWorker(name, jvmId)

            s"""$setting
               |
               |$typedef""".stripMargin
          }

          val typedef =
            s"""trait $baseModule ${mkExtends(supertypes)} {
               |
               |${setJavacOptions(javacOptions)}
               |
               |${setRepositories(reps.iterator)}
               |
               |${setPomSettings(pomSettings)}
               |
               |${setPublishVersion(publishVersion)}
               |
               |$zincWorker
               |}""".stripMargin

          (javacOptions, reps, pomSettings, publishVersion, typedef)
        case None =>
          (Seq.empty, Seq.empty, "", "", "")
      }

    val nestedModuleImports = cfg.baseModule.map(name => s"$$file.$name")
    val moduleSupertype = cfg.baseModule.getOrElse("MavenModule")

    input.map { case build @ Node(dirs, project) =>
      val name = project.name()
      println(s"converting module $name")

      val millSourcePath = os.Path(project.directory())
      val pom = project.maven().pom()

      val hasPom = null != pom
      val hasSource = os.exists(millSourcePath / "src")
      val hasTest = os.exists(millSourcePath / "src/test")
      val isNested = dirs.nonEmpty

      val imports = {
        val b = SortedSet.newBuilder[String]
        b += "mill._"
        b += "mill.javalib._"
        b += "mill.javalib.publish._"
        if (isNested) b ++= nestedModuleImports
        else if (isMonorepo) b += "$packages._"
        b.result()
      }

      val supertypes = {
        val b = Seq.newBuilder[String]
        b += "RootModule"
        if (hasPom && basePomSettings.isEmpty) b += "PublishModule"
        if (isNested || hasSource) b += moduleSupertype
        b.result()
      }

      val (
        companions,
        mainBomIvyDeps,
        mainIvyDeps,
        mainModuleDeps,
        mainCompileIvyDeps,
        mainCompileModuleDeps,
        mainRunIvyDeps,
        mainRunModuleDeps,
        testModule,
        testBomIvyDeps,
        testIvyDeps,
        testModuleDeps,
        testCompileIvyDeps,
        testCompileModuleDeps
      ) = scopedDeps(project, packages, cfg)

      val inner = {
        val javacOptions = {
          val options = getJavacOptions(project).diff(baseJavacOptions)
          if (options == baseJavacOptions) Seq.empty else options
        }
        val reps = getRepositories(project).diff(baseReps)
        val pomSettings = {
          val setting = mkPomSettings(project)
          if (setting == basePomSettings) null else setting
        }
        val publishVersion = {
          val version = getPublishVersion(project)
          if (version == basePublishVersion) null else version
        }

        val testModuleTypedef = {
          if (hasTest) {
            val name = backtickWrap(cfg.testModule)
            val declare = testModule match {
              case Some(supertype) => s"object $name extends MavenTests with $supertype"
              case None => s"trait $name extends MavenTests"
            }

            s"""$declare {
               |
               |${setBomIvyDeps(testBomIvyDeps)}
               |
               |${setIvyDeps(testIvyDeps)}
               |
               |${setModuleDeps(testModuleDeps)}
               |
               |${setCompileIvyDeps(testCompileIvyDeps)}
               |
               |${setCompileModuleDeps(testCompileModuleDeps)}
               |}""".stripMargin
          } else ""
        }

        s"""${setArtifactName(project.name(), dirs)}
           |
           |${setJavacOptions(javacOptions)}
           |
           |${setRepositories(reps)}
           |
           |${setBomIvyDeps(mainBomIvyDeps)}
           |
           |${setIvyDeps(mainIvyDeps)}
           |
           |${setModuleDeps(mainModuleDeps)}
           |
           |${setCompileIvyDeps(mainCompileIvyDeps)}
           |
           |${setCompileModuleDeps(mainCompileModuleDeps)}
           |
           |${setRunIvyDeps(mainRunIvyDeps)}
           |
           |${setRunModuleDeps(mainRunModuleDeps)}
           |
           |${setPomSettings(pomSettings)}
           |
           |${setPublishVersion(publishVersion)}
           |
           |$testModuleTypedef""".stripMargin
      }

      val outer = if (isNested) "" else baseModuleTypedef

      build.copy(module = BuildObject(imports, companions, supertypes, inner, outer))
    }
  }

  def gav(dep: JavaModel.Dep): Gav =
    (dep.group(), dep.name(), dep.version())

  def getJavacOptions(project: ProjectModel): Seq[String] = {
    val _java = project._java()
    if (null == _java) Seq.empty
    else _java.javacOptions().asScala.toSeq
  }

  def getRepositories(project: ProjectModel): Seq[String] =
    project.maven().reps().asScala.toSeq.sorted.map(uri =>
      s"coursier.maven.MavenRepository(${escape(uri.toString)})"
    )

  def getPublishVersion(project: ProjectModel): String =
    project.version() match {
      case "" | "unspecified" => null
      case version => version
    }

  val interpIvy: JavaModel.Dep => InterpIvy = dep =>
    BuildGenUtil.interpIvy(dep.group(), dep.name(), dep.version())

  def mkPomSettings(project: ProjectModel): String = {
    val pom = project.maven.pom()
    if (null == pom) ""
    else {
      val licenses = pom.licenses().iterator().asScala
        .map(lic => mkLicense(lic.name(), lic.name(), lic.url()))
      val versionControl = Option(pom.scm()).fold(mkVersionControl())(scm =>
        mkVersionControl(scm.url(), scm.connection(), scm.devConnection(), scm.tag())
      )
      val developers = pom.devs().iterator().asScala
        .map(dev => mkDeveloper(dev.id(), dev.name(), dev.url(), dev.org(), dev.orgUrl()))

      BuildGenUtil.mkPomSettings(
        pom.description(),
        project.group(), // Mill uses group for POM org
        pom.url(),
        licenses,
        versionControl,
        developers
      )
    }
  }

  def scopedDeps(
      project: ProjectModel,
      packages: PartialFunction[Gav, BuildPackage],
      cfg: BuildGenConfig
  ): (
      Companions,
      BomIvyDeps,
      IvyDeps,
      ModuleDeps,
      IvyDeps,
      ModuleDeps,
      IvyDeps,
      ModuleDeps,
      Option[String],
      BomIvyDeps,
      IvyDeps,
      ModuleDeps,
      IvyDeps,
      ModuleDeps
  ) = {
    val namedIvyDeps = Seq.newBuilder[(String, String)]
    val mainBomIvyDeps = SortedSet.newBuilder[String]
    val mainIvyDeps = SortedSet.newBuilder[String]
    val mainModuleDeps = SortedSet.newBuilder[String]
    val mainCompileIvyDeps = SortedSet.newBuilder[String]
    val mainCompileModuleDeps = SortedSet.newBuilder[String]
    val mainRunIvyDeps = SortedSet.newBuilder[String]
    val mainRunModuleDeps = SortedSet.newBuilder[String]
    var testModule = Option.empty[String]
    val testBomIvyDeps = SortedSet.newBuilder[String]
    val testIvyDeps = SortedSet.newBuilder[String]
    val testModuleDeps = SortedSet.newBuilder[String]
    val testCompileIvyDeps = SortedSet.newBuilder[String]
    val testCompileModuleDeps = SortedSet.newBuilder[String]

    val hasTest = os.exists(os.Path(project.directory()) / "src/test")
    val _java = project._java()
    if (null != _java) {
      val ivyDep: JavaModel.Dep => InterpIvy = cfg.depsObject.fold(interpIvy) { objName => dep =>
        val depName = s"`${dep.group()}:${dep.name()}`"
        namedIvyDeps += ((depName, interpIvy(dep)))
        s"$objName.$depName"
      }
      _java.configs().iterator().asScala.foreach { config =>
        import JavaPlugin.*

        val conf = config.name()
        conf match {
          case IMPLEMENTATION_CONFIGURATION_NAME | API_CONFIGURATION_NAME =>
            config.deps.iterator().asScala
              .foreach { dep =>
                val id = gav(dep)
                if (packages.isDefinedAt(id)) mainModuleDeps += packages(id)
                else if (isBom(id)) {
                  println(s"assuming $conf dependency $id is a BOM")
                  mainBomIvyDeps += ivyDep(dep)
                } else mainIvyDeps += ivyDep(dep)
              }
          case COMPILE_ONLY_CONFIGURATION_NAME | COMPILE_ONLY_API_CONFIGURATION_NAME =>
            config.deps.iterator().asScala
              .foreach { dep =>
                val id = gav(dep)
                if (packages.isDefinedAt(id)) mainCompileModuleDeps += packages(id)
                else mainCompileIvyDeps += ivyDep(dep)
              }
          case RUNTIME_ONLY_CONFIGURATION_NAME =>
            config.deps.iterator().asScala
              .foreach { dep =>
                val id = gav(dep)
                if (packages.isDefinedAt(id)) mainRunModuleDeps += packages(id)
                else mainRunIvyDeps += ivyDep(dep)
              }
          case TEST_IMPLEMENTATION_CONFIGURATION_NAME =>
            config.deps.iterator().asScala
              .foreach { dep =>
                val id = gav(dep)
                if (packages.isDefinedAt(id)) testModuleDeps += packages(id)
                else
                  (if (isBom(id)) testBomIvyDeps
                   else {
                     println(s"assuming $conf dependency $id is a BOM")
                     testIvyDeps
                   }) += ivyDep(dep)
                if (hasTest && testModule.isEmpty) {
                  testModule = testModulesByGroup.get(dep.group())
                }
              }
          case TEST_COMPILE_ONLY_CONFIGURATION_NAME =>
            config.deps.iterator().asScala
              .foreach { dep =>
                val id = gav(dep)
                if (packages.isDefinedAt(id)) testCompileModuleDeps += packages(id)
                else testCompileIvyDeps += ivyDep(dep)
              }
          case name =>
            config.deps.iterator().asScala
              .foreach { dep =>
                val id = gav(dep)
                println(s"ignoring $name dependency $id")
              }
        }
      }
    }
    val companions = cfg.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
      SortedMap((name, SortedMap(namedIvyDeps.result() *)))
    )
    (
      companions,
      mainBomIvyDeps.result(),
      mainIvyDeps.result(),
      mainModuleDeps.result(),
      mainCompileIvyDeps.result(),
      mainCompileModuleDeps.result(),
      mainRunIvyDeps.result(),
      mainRunModuleDeps.result(),
      testModule,
      testBomIvyDeps.result(),
      testIvyDeps.result(),
      testModuleDeps.result(),
      testCompileIvyDeps.result(),
      testCompileModuleDeps.result()
    )
  }
}

@main
@mill.api.internal
case class BuildGenConfig(
    @arg(doc = "name of generated base module trait defining shared settings", short = 'b')
    baseModule: Option[String] = None,
    @arg(doc = "name of Gradle project to extract settings for --base-module", short = 'g')
    baseProject: Option[String] = None,
    @arg(doc = "version of custom JVM to configure in --base-module", short = 'j')
    jvmId: Option[String] = None,
    @arg(doc = "name of generated nested test module", short = 't')
    testModule: String = "test",
    @arg(doc = "name of generated companion object defining dependency constants", short = 'd')
    depsObject: Option[String] = None,
    @arg(doc = "merge build files generated for a multi-module build", short = 'm')
    merge: Flag = Flag()
)
