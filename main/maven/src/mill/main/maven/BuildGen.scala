package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.BuildObject.Companions
import mill.main.buildgen.{BuildGenUtil, BuildObject, Node, Tree}
import mill.runner.FileImportGraph.backtickWrap
import org.apache.maven.model.{Dependency, Model, Parent}

import scala.collection.immutable.{SortedMap, SortedSet}
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
object BuildGen {

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[BuildGenConfig].constructOrExit(args.toSeq)
    run(cfg)
  }

  private type MavenNode = Node[Model]

  private def run(cfg: BuildGenConfig): Unit = {
    val workspace = os.pwd

    println("converting Maven build")
    val modeler = Modeler(cfg)
    val input = Tree.from(Seq.empty[String]) { dirs =>
      val model = modeler(workspace / dirs)
      (Node(dirs, model), model.getModules.iterator().asScala.map(dirs :+ _))
    }

    val output = convert(input, cfg)
    write(if (cfg.merge.value) compact(output) else output)

    println("converted Maven build to Mill")
  }

  private def convert(input: Tree[MavenNode], cfg: BuildGenConfig): Tree[Node[BuildObject]] = {
    val packages = // for resolving moduleDeps
      buildPackages(input)(model => (model.getGroupId, model.getArtifactId, model.getVersion))
    val isMonorepo = packages.size > 1

    val moduleSupertypes = Seq("PublishModule", "MavenModule")

    val (
      baseJavacOptions,
      baseNoPom,
      basePublishVersion,
      basePublishProperties,
      baseModuleTypedef
    ) = cfg.baseModule match {
      case Some(baseModule) =>
        val model = input.node.module
        val javacOptions = Plugins.MavenCompilerPlugin.javacOptions(model)
        val pomSettings = mkPomSettings(model)
        val publishVersion = model.getVersion
        val publishProperties = getPublishProperties(model, cfg)

        val zincWorker = cfg.jvmId.fold("") { jvmId =>
          val name = s"${baseModule}ZincWorker"
          val setting = setZincWorker(name)
          val typedef = mkZincWorker(name, jvmId)

          s"""$setting
             |
             |$typedef""".stripMargin
        }

        val typedef =
          s"""trait $baseModule ${mkExtends(moduleSupertypes)} {
             |
             |${setJavacOptions(javacOptions)}
             |
             |${setPomSettings(pomSettings)}
             |
             |${setPublishVersion(publishVersion)}
             |
             |${setPublishProperties(publishProperties)}
             |
             |$zincWorker
             |}""".stripMargin

        (javacOptions, pomSettings.isEmpty, publishVersion, publishProperties, typedef)
      case None =>
        (Seq.empty, true, "", Seq.empty, "")
    }

    val nestedModuleImports = cfg.baseModule.map(name => s"$$file.$name")

    input.map { case build @ Node(dirs, model) =>
      val artifactId = model.getArtifactId
      println(s"converting module $artifactId")

      val millSourcePath = os.Path(model.getProjectDirectory)
      val packaging = model.getPackaging

      val isNested = dirs.nonEmpty
      val hasTest = os.exists(millSourcePath / "src/test")

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
        cfg.baseModule.fold(b ++= moduleSupertypes)(b += _)
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
        testModuleDeps
      ) = scopedDeps(model, packages, cfg)

      val inner = {
        val javacOptions = {
          val options = Plugins.MavenCompilerPlugin.javacOptions(model)
          if (options == baseJavacOptions) Seq.empty else options
        }
        val pomSettings = if (baseNoPom) mkPomSettings(model) else null
        val resources = model.getBuild.getResources.iterator().asScala
          .map(_.getDirectory)
          .map(os.Path(_).subRelativeTo(millSourcePath))
          .filterNot(_ == mavenMainResourceDir)
        val publishVersion = {
          val version = model.getVersion
          if (version == basePublishVersion) null else version
        }
        val publishProperties = getPublishProperties(model, cfg).diff(basePublishProperties)
        val pomParentArtifact = mkPomParent(model.getParent)

        val testModuleTypedef =
          if (hasTest) {
            val name = backtickWrap(cfg.testModule)
            val declare = testModule match {
              case Some(supertype) => s"object $name extends MavenTests with $supertype"
              case None => s"trait $name extends MavenTests"
            }
            val resources = model.getBuild.getTestResources.iterator().asScala
              .map(_.getDirectory)
              .map(os.Path(_).subRelativeTo(millSourcePath))
              .filterNot(_ == mavenTestResourceDir)

            s"""$declare {
               |
               |${setBomIvyDeps(testBomIvyDeps)}
               |
               |${setIvyDeps(testIvyDeps)}
               |
               |${setModuleDeps(testModuleDeps)}
               |
               |${setResources(resources)}
               |}""".stripMargin
          } else ""

        s"""${setBomIvyDeps(mainBomIvyDeps)}
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
           |${setJavacOptions(javacOptions)}
           |
           |${setResources(resources)}
           |
           |${setArtifactName(artifactId, dirs)}
           |
           |${setPomPackaging(packaging)}
           |
           |${setPomParentProject(pomParentArtifact)}
           |
           |${setPomSettings(pomSettings)}
           |
           |${setPublishVersion(publishVersion)}
           |
           |${setPublishProperties(publishProperties)}
           |
           |$testModuleTypedef""".stripMargin
      }

      val outer = if (isNested) "" else baseModuleTypedef

      build.copy(module = BuildObject(imports, companions, supertypes, inner, outer))
    }
  }

  def gav(dep: Dependency): (String, String, String) =
    (dep.getGroupId, dep.getArtifactId, dep.getVersion)

  def getPublishProperties(model: Model, cfg: BuildGenConfig): Seq[(String, String)] =
    if (cfg.publishProperties.value) {
      val props = model.getProperties
      props.stringPropertyNames().iterator().asScala
        .map(key => (key, props.getProperty(key)))
        .toSeq
        .sorted
    } else Seq.empty

  val interpIvy: Dependency => String = dep =>
    BuildGenUtil.ivyString(
      dep.getGroupId,
      dep.getArtifactId,
      dep.getVersion,
      dep.getType,
      dep.getClassifier,
      dep.getExclusions.iterator().asScala.map(x => (x.getGroupId, x.getArtifactId))
    )

  def mkPomParent(parent: Parent): String =
    if (null == parent) null
    else mkArtifact(parent.getGroupId, parent.getArtifactId, parent.getVersion)

  def mkPomSettings(model: Model): String = {
    val licenses = model.getLicenses.iterator().asScala
      .map(lic =>
        mkLicense(
          lic.getName,
          lic.getName,
          lic.getUrl,
          isOsiApproved = false,
          isFsfLibre = false,
          "repo"
        )
      )
    val versionControl = Option(model.getScm).fold(mkVersionControl())(scm =>
      mkVersionControl(scm.getUrl, scm.getConnection, scm.getDeveloperConnection, scm.getTag)
    )
    val developers = model.getDevelopers.iterator().asScala
      .map(dev =>
        mkDeveloper(dev.getId, dev.getName, dev.getUrl, dev.getOrganization, dev.getOrganizationUrl)
      )

    BuildGenUtil.mkPomSettings(
      model.getDescription,
      model.getGroupId, // Mill uses group for POM org
      model.getUrl,
      licenses,
      versionControl,
      developers
    )
  }

  def scopedDeps(model: Model, packages: PartialFunction[(String, String, String), String], cfg: BuildGenConfig): (
      Companions,
      IterableOnce[String],
      IterableOnce[String],
      IterableOnce[String],
      IterableOnce[String],
      IterableOnce[String],
      IterableOnce[String],
      IterableOnce[String],
      Option[String],
      IterableOnce[String],
      IterableOnce[String],
      IterableOnce[String]
  ) = {
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

    val hasTest = os.exists(os.Path(model.getProjectDirectory) / "src/test")
    val namedIvyDeps = Seq.newBuilder[(String, String)]
    val ivyDep: Dependency => String = {
      cfg.depsObject.fold(interpIvy) { objName => dep =>
        {
          val depName = s"`${dep.getGroupId}:${dep.getArtifactId}`"
          namedIvyDeps += ((depName, interpIvy(dep)))
          s"$objName.$depName"
        }
      }
    }

    model.getDependencies.asScala.foreach { dep =>
      val id = gav(dep)
      dep.getScope match {
        case "compile" if packages.isDefinedAt(id) =>
          mainCompileModuleDeps += packages(id)
        case "compile" if isBom(id) =>
          println(s"assuming compile dependency $id is a BOM")
          mainIvyDeps += ivyDep(dep)
        case "compile" =>
          mainIvyDeps += ivyDep(dep)
        case "provided" if packages.isDefinedAt(id) =>
          mainModuleDeps += packages(id)
        case "provided" =>
          mainCompileIvyDeps += ivyDep(dep)
        case "runtime" if packages.isDefinedAt(id) =>
          mainRunModuleDeps += packages(id)
        case "runtime" =>
          mainRunIvyDeps += ivyDep(dep)
        case "test" if packages.isDefinedAt(id) =>
          testModuleDeps += packages(id)
        case "test" if isBom(id) =>
          println(s"assuming test dependency $id is a BOM")
          testBomIvyDeps += ivyDep(dep)
        case "test" =>
          testIvyDeps += ivyDep(dep)
        case scope =>
          println(s"ignoring $scope dependency $id")

      }
      if (hasTest && testModule.isEmpty) {
        testModule = testModulesByGroup.get(dep.getGroupId)
      }
    }

    val companions = cfg.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
      SortedMap((name, SortedMap(namedIvyDeps.result() *)))
    )

    (
      companions,
      mainBomIvyDeps.result(),
      mainIvyDeps.result(),
      mainCompileModuleDeps.result(),
      mainCompileIvyDeps.result(),
      mainModuleDeps.result(),
      mainRunIvyDeps.result(),
      mainRunModuleDeps.result(),
      testModule,
      testBomIvyDeps.result(),
      testIvyDeps.result(),
      testModuleDeps.result()
    )
  }
}

@main
@mill.api.internal
case class BuildGenConfig(
    @arg(doc = "name of generated base module trait defining shared settings", short = 'b')
    baseModule: Option[String] = None,
    @arg(doc = "version of custom JVM to configure in --base-module", short = 'j')
    jvmId: Option[String] = None,
    @arg(doc = "name of generated nested test module", short = 't')
    testModule: String = "test",
    @arg(doc = "name of generated companion object defining dependency constants", short = 'd')
    depsObject: Option[String] = None,
    @arg(doc = "merge build files generated for a multi-module build", short = 'm')
    merge: Flag = Flag(),
    @arg(doc = "capture properties defined in `pom.xml` for publishing", short = 'p')
    publishProperties: Flag = Flag(),
    @arg(doc = "use cache for Maven repository system")
    cacheRepository: Flag = Flag(),
    @arg(doc = "process Maven plugin executions and configurations")
    processPlugins: Flag = Flag()
) extends ModelerConfig
