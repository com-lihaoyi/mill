package mill.main.gradle

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.{BuildGenUtil, BuildObject, Node, Tree}
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
 */
@mill.api.internal
object BuildGen {

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[BuildGenConfig].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: BuildGenConfig): Unit = {
    val workspace = os.pwd

    println("converting Gradle build")
    val connector = GradleConnector.newConnector()

    val args =
      cfg.shared.jvmId.map { id =>
        println(s"resolving Java home for jvmId $id")
        val home = Jvm.resolveJavaHome(id).getOrThrow
        s"-Dorg.gradle.java.home=$home"
      } ++ Seq("--init-script", writeGradleInitScript.toString())

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
        write(if (cfg.merge.value) compactBuildTree(output) else output)

        println("converted Gradle build to Mill")
      } finally connection.close()
    } finally connector.disconnect()
  }

  private def writeGradleInitScript: os.Path = {
    val file = os.temp.dir() / "init.gradle"
    val classpath =
      os.Path(classOf[ProjectTreePlugin].getProtectionDomain.getCodeSource.getLocation.toURI)
    val plugin = classOf[ProjectTreePlugin].getName
    val contents =
      s"""initscript {
         |    dependencies {
         |        classpath files(${escape(classpath.toString()) /* escape for Windows */})
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

  private def convert(
      input: Tree[Node[ProjectModel]],
      cfg: BuildGenConfig
  ): Tree[Node[BuildObject]] = {
    val packages = // for resolving moduleDeps
      buildPackages(input)(project => (project.group(), project.name(), project.version()))

    val (
      baseJavacOptions,
      baseRepos,
      baseNoPom,
      basePublishVersion,
      basePublishProperties,
      baseModuleTypedef
    ) =
      cfg.shared.baseModule match {
        case Some(baseModule) =>
          val project = {
            val projects = input.nodes(Tree.Traversal.BreadthFirst).map(_.module).toSeq
            cfg.baseProject
              .flatMap(name => projects.collectFirst { case m if name == m.name => m })
              .orElse(projects.collectFirst { case m if null != m.maven().pom() => m })
              .orElse(projects.collectFirst { case m if !m.maven().repositories().isEmpty => m })
              .getOrElse(input.node.module)
          }
          if (packages.size > 1) {
            println(s"settings from ${project.name()} will be shared in base module")
          }
          val supertypes =
            Seq("MavenModule") ++
              Option.when(null != project.maven().pom()) { "PublishModule" }

          val javacOptions = getJavacOptions(project)
          val repos = getRepositories(project)
          val pomSettings = renderPomSettings(project)
          val publishVersion = getPublishVersion(project)

          val typedef = renderTrait(
            cfg.shared.jvmId,
            baseModule,
            supertypes,
            javacOptions,
            pomSettings,
            publishVersion,
            Nil
          )

          (javacOptions, repos, pomSettings.isEmpty, publishVersion, Seq.empty, typedef)
        case None =>
          (Seq.empty, Seq.empty, true, "", Seq.empty, "")
      }

    val moduleSupertype = cfg.shared.baseModule.getOrElse("MavenModule")

    input.map { case build @ Node(dirs, project) =>
      val name = project.name()
      println(s"converting module $name")

      val millSourcePath = os.Path(project.directory())
      val pom = project.maven().pom()

      val hasPom = null != pom
      val hasSource = os.exists(millSourcePath / "src")
      val hasTest = os.exists(millSourcePath / "src/test")
      val isNested = dirs.nonEmpty

      val imports = renderImports(cfg.shared.baseModule, isNested, packages.size)

      val supertypes =
        Seq("RootModule") ++
          Option.when(hasPom && baseNoPom) { "PublishModule" } ++
          Option.when(isNested || hasSource) { moduleSupertype }

      val scopedDeps = new ScopedDeps(project, packages, cfg)

      val inner = {
        val javacOptions = {
          val options = getJavacOptions(project).diff(baseJavacOptions)
          if (options == baseJavacOptions) Seq.empty else options
        }
        val repos = getRepositories(project).diff(baseRepos)
        val pomSettings = if (baseNoPom) renderPomSettings(project) else null
        val publishVersion = {
          val version = getPublishVersion(project)
          if (version == basePublishVersion) null else version
        }
        BuildGenUtil.renderModule(
          scopedDeps,
          cfg.shared.testModule,
          hasTest,
          dirs,
          repos,
          javacOptions,
          project.name(),
          pomSettings,
          publishVersion,
          null,
          null
        )
      }

      val outer = if (isNested) "" else baseModuleTypedef

      build.copy(module =
        BuildObject(imports.to(SortedSet), scopedDeps.companions, supertypes, inner, outer)
      )
    }
  }

  def groupArtifactVersion(dep: JavaModel.Dep): (String, String, String) =
    (dep.group(), dep.name(), dep.version())

  def getJavacOptions(project: ProjectModel): Seq[String] = {
    val _java = project._java()
    if (null == _java) Seq.empty
    else _java.javacOptions().asScala.toSeq
  }

  def getRepositories(project: ProjectModel): Seq[String] =
    project.maven().repositories().asScala.toSeq.sorted.map(uri =>
      s"coursier.maven.MavenRepository(${escape(uri.toString)})"
    )

  def getPublishVersion(project: ProjectModel): String =
    project.version() match {
      case "" | "unspecified" => null
      case version => version
    }

  val interpIvy: JavaModel.Dep => String = dep =>
    BuildGenUtil.renderIvyString(dep.group(), dep.name(), dep.version())

  def renderPomSettings(project: ProjectModel): String = {
    val pom = project.maven.pom()
    if (null == pom) ""
    else {
      val licenses = pom.licenses().iterator().asScala
        .map(lic => mrenderLicense(lic.name(), lic.name(), lic.url()))
      val versionControl = Option(pom.scm()).fold(renderVersionControl())(scm =>
        renderVersionControl(scm.url(), scm.connection(), scm.devConnection(), scm.tag())
      )
      val developers = pom.devs().iterator().asScala
        .map(dev => renderDeveloper(dev.id(), dev.name(), dev.url(), dev.org(), dev.orgUrl()))

      BuildGenUtil.renderPomSettings(
        pom.description(),
        project.group(), // Mill uses group for POM org
        pom.url(),
        licenses,
        versionControl,
        developers
      )
    }
  }

  class ScopedDeps(
      project: ProjectModel,
      packages: PartialFunction[(String, String, String), String],
      cfg: BuildGenConfig
  ) extends BuildGenUtil.ScopedDeps {

    val hasTest = os.exists(os.Path(project.directory()) / "src/test")
    val _java = project._java()
    if (null != _java) {
      val ivyDep: JavaModel.Dep => String =
        cfg.shared.depsObject.fold(interpIvy) { objName => dep =>
          val depName = s"`${dep.group()}:${dep.name()}`"
          namedIvyDeps += ((depName, interpIvy(dep)))
          s"$objName.$depName"
        }
      _java.configs().forEach { config =>
        import JavaPlugin.*

        val conf = config.name()
        conf match {
          case IMPLEMENTATION_CONFIGURATION_NAME | API_CONFIGURATION_NAME =>
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
              if (packages.isDefinedAt(id)) mainModuleDeps += packages(id)
              else if (isBom(id)) {
                println(s"assuming $conf dependency $id is a BOM")
                mainBomIvyDeps += ivyDep(dep)
              } else mainIvyDeps += ivyDep(dep)
            }
          case COMPILE_ONLY_CONFIGURATION_NAME | COMPILE_ONLY_API_CONFIGURATION_NAME =>
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
              if (packages.isDefinedAt(id)) mainCompileModuleDeps += packages(id)
              else mainCompileIvyDeps += ivyDep(dep)
            }
          case RUNTIME_ONLY_CONFIGURATION_NAME =>
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
              if (packages.isDefinedAt(id)) mainRunModuleDeps += packages(id)
              else mainRunIvyDeps += ivyDep(dep)
            }
          case TEST_IMPLEMENTATION_CONFIGURATION_NAME =>
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
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
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
              if (packages.isDefinedAt(id)) testCompileModuleDeps += packages(id)
              else testCompileIvyDeps += ivyDep(dep)
            }
          case name =>
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
              println(s"ignoring $name dependency $id")
            }
        }
      }
    }
    val companions =
      cfg.shared.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
        SortedMap((name, SortedMap(namedIvyDeps.toSeq *)))
      )
  }
}

@main
@mill.api.internal
case class BuildGenConfig(
    shared: BuildGenUtil.Config,
    @arg(doc = "name of Gradle project to extract settings for --base-module", short = 'g')
    baseProject: Option[String] = None,
    @arg(doc = "merge build files generated for a multi-module build", short = 'm')
    merge: Flag = Flag()
)
