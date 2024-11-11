package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.build.{BuildObject, Node, Tree}
import mill.runner.FileImportGraph.backtickWrap
import org.apache.maven.model.{Dependency, Model}

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
 *  - captures project metadata
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
  private type MillNode = Node[BuildObject]

  private def run(cfg: BuildGenConfig): Unit = {
    val workspace = os.pwd

    println("converting Maven build")
    val modeler = Modeler(cfg)
    val input = Tree.from(Seq.empty[String]) { dirs =>
      val model = modeler(workspace / dirs)
      (Node(dirs, model), model.getModules.iterator().asScala.map(dirs :+ _))
    }

    var output = convert(input, cfg)
    if (cfg.merge.value) {
      println("compacting Mill build tree")
      output = output.merge
    }

    val nodes = output.toSeq
    println(s"generated ${nodes.length} Mill build file(s)")

    println("removing existing Mill build files")
    os.walk.stream(workspace, skip = (workspace / "out").equals)
      .filter(_.ext == ".mill")
      .foreach(os.remove.apply)

    nodes.foreach { node =>
      val file = node.file
      val source = node.source
      println(s"writing Mill build file to $file")
      os.write(workspace / file, source)
    }

    println("converted Maven build to Mill")
  }

  private def convert(input: Tree[MavenNode], cfg: BuildGenConfig): Tree[MillNode] = {
    val packages = // for resolving moduleDeps
      input
        .fold(Map.newBuilder[Id, Package])((z, build) => z += ((Id(build.module), build.pkg)))
        .result()

    val baseModuleTypedef = cfg.baseModule.fold("") { baseModule =>
      val metadataSettings = metadata(input.node.module, cfg)

      s"""trait $baseModule extends PublishModule with MavenModule {
         |
         |$metadataSettings
         |}""".stripMargin
    }

    input.map { case build @ Node(dirs, model) =>
      val packaging = model.getPackaging
      val millSourcePath = os.Path(model.getProjectDirectory)

      val imports = {
        val b = SortedSet.newBuilder[String]
        b += "mill._"
        b += "mill.api._"
        b += "mill.javalib._"
        b += "mill.javalib.publish._"
        if (dirs.nonEmpty) cfg.baseModule.foreach(baseModule => b += s"$$file.$baseModule")
        else if (packages.size > 1) b += "$packages._"
        b.result()
      }

      val supertypes = {
        val b = Seq.newBuilder[String]
        b += "RootModule"
        cfg.baseModule.fold(b += "PublishModule" += "MavenModule")(b += _)
        b.result()
      }

      val (companions, compileDeps, providedDeps, runtimeDeps, testDeps, testModule) =
        Scoped.all(model, packages, cfg)

      val inner = {
        val javacOptions = Plugins.MavenCompilerPlugin.javacOptions(model)

        val artifactNameSetting = {
          val id = model.getArtifactId
          val name = if (dirs.nonEmpty && dirs.last == id) null else s"\"$id\"" // skip default
          optional("override def artifactName = ", name)
        }
        val resourcesSetting =
          resources(
            model.getBuild.getResources.iterator().asScala
              .map(_.getDirectory)
              .map(os.Path(_))
              .filterNot((millSourcePath / "src/main/resources").equals)
              .map(_.relativeTo(millSourcePath))
          )
        val javacOptionsSetting =
          optional("override def javacOptions = Seq(\"", javacOptions, "\",\"", "\")")
        val depsSettings = compileDeps.settings("ivyDeps", "moduleDeps")
        val compileDepsSettings = providedDeps.settings("compileIvyDeps", "compileModuleDeps")
        val runDepsSettings = runtimeDeps.settings("runIvyDeps", "runModuleDeps")
        val pomPackagingTypeSetting = {
          val packagingType = packaging match {
            case "jar" => null // skip default
            case "pom" => "PackagingType.Pom"
            case pkg => s"\"$pkg\""
          }
          optional(s"override def pomPackagingType = ", packagingType)
        }
        val pomParentProjectSetting = {
          val parent = model.getParent
          if (null == parent) ""
          else {
            val group = parent.getGroupId
            val id = parent.getArtifactId
            val version = parent.getVersion
            s"override def pomParentProject = Some(Artifact(\"$group\", \"$id\", \"$version\"))"
          }
        }
        val metadataSettings = if (cfg.baseModule.isEmpty) metadata(model, cfg) else ""
        val testModuleTypedef = {
          val resources = model.getBuild.getTestResources.iterator().asScala
            .map(_.getDirectory)
            .map(os.Path(_))
            .filterNot((millSourcePath / "src/test/resources").equals)
          if (
            "pom" != packaging && (
              os.exists(millSourcePath / "src/test") || resources.nonEmpty || testModule.nonEmpty
            )
          ) {
            val supertype = "MavenTests"
            val testMillSourcePath = millSourcePath / "test"
            val resourcesRel = resources.map(_.relativeTo(testMillSourcePath))

            testDeps.testTypeDef(supertype, testModule, resourcesRel, cfg)
          } else ""
        }

        s"""$artifactNameSetting
           |
           |$resourcesSetting
           |
           |$javacOptionsSetting
           |
           |$depsSettings
           |
           |$compileDepsSettings
           |
           |$runDepsSettings
           |
           |$pomPackagingTypeSetting
           |
           |$pomParentProjectSetting
           |
           |$metadataSettings
           |
           |$testModuleTypedef""".stripMargin
      }

      val outer = if (dirs.isEmpty) baseModuleTypedef else ""

      build.copy(module = BuildObject(imports, companions, supertypes, inner, outer))
    }
  }

  private type Id = (String, String, String)
  private object Id {

    def apply(mvn: Dependency): Id =
      (mvn.getGroupId, mvn.getArtifactId, mvn.getVersion)

    def apply(mvn: Model): Id =
      (mvn.getGroupId, mvn.getArtifactId, mvn.getVersion)
  }

  private type Package = String
  private type ModuleDeps = SortedSet[Package]
  private type IvyInterp = String
  private type IvyDeps = SortedSet[String] // interpolated or named

  private case class Scoped(ivyDeps: IvyDeps, moduleDeps: ModuleDeps) {

    def settings(ivyDepsName: String, moduleDepsName: String): String = {
      val ivyDepsSetting =
        optional(s"override def $ivyDepsName = Agg", ivyDeps)
      val moduleDepsSetting =
        optional(s"override def $moduleDepsName = Seq", moduleDeps)

      s"""$ivyDepsSetting
         |
         |$moduleDepsSetting""".stripMargin
    }

    def testTypeDef(
        supertype: String,
        testModule: Scoped.TestModule,
        resourcesRel: IterableOnce[os.RelPath],
        cfg: BuildGenConfig
    ): String =
      if (ivyDeps.isEmpty && moduleDeps.isEmpty) ""
      else {
        val name = backtickWrap(cfg.testModule)
        val declare = testModule match {
          case Some(module) => s"object $name extends $supertype with $module"
          case None => s"trait $name extends $supertype"
        }
        val resourcesSetting = resources(resourcesRel)
        val moduleDepsSetting =
          optional(s"override def moduleDeps = super.moduleDeps ++ Seq", moduleDeps)
        val ivyDepsSetting = optional(s"override def ivyDeps = super.ivyDeps() ++ Agg", ivyDeps)

        s"""$declare {
           |
           |$resourcesSetting
           |
           |$moduleDepsSetting
           |
           |$ivyDepsSetting
           |}""".stripMargin
      }
  }
  private object Scoped {

    private type Compile = Scoped
    private type Provided = Scoped
    private type Runtime = Scoped
    private type Test = Scoped
    private type TestModule = Option[String]

    def all(
        model: Model,
        packages: PartialFunction[Id, Package],
        cfg: BuildGenConfig
    ): (BuildObject.Companions, Compile, Provided, Runtime, Test, TestModule) = {
      val compileIvyDeps = SortedSet.newBuilder[String]
      val providedIvyDeps = SortedSet.newBuilder[String]
      val runtimeIvyDeps = SortedSet.newBuilder[String]
      val testIvyDeps = SortedSet.newBuilder[String]
      val compileModuleDeps = SortedSet.newBuilder[String]
      val providedModuleDeps = SortedSet.newBuilder[String]
      val runtimeModuleDeps = SortedSet.newBuilder[String]
      val testModuleDeps = SortedSet.newBuilder[String]
      var testModule = Option.empty[String]

      val notPom = "pom" != model.getPackaging
      val ivyInterp: Dependency => IvyInterp = {
        val module = model.getProjectDirectory.getName
        dep => {
          val group = dep.getGroupId
          val id = dep.getArtifactId
          val version = dep.getVersion
          val tpe = dep.getType match {
            case null | "" | "jar" => "" // skip default
            case tpe => s";type=$tpe"
          }
          val classifier = dep.getClassifier match {
            case null | "" => ""
            case s"$${$v}" => // drop values like ${os.detected.classifier}
              println(s"[$module] dropping classifier $${$v} for dependency $group:$id:$version")
              ""
            case classifier => s";classifier=$classifier"
          }
          val exclusions = dep.getExclusions.iterator.asScala
            .map(x => s";exclude=${x.getGroupId}:${x.getArtifactId}")
            .mkString

          s"ivy\"$group:$id:$version$tpe$classifier$exclusions\""
        }
      }
      val namedIvyDeps = Seq.newBuilder[(String, IvyInterp)]
      val ivyDep: Dependency => String = {
        cfg.depsObject.fold(ivyInterp) { objName => dep =>
          {
            val depName = backtickWrap(s"${dep.getGroupId}:${dep.getArtifactId}")
            namedIvyDeps += ((depName, ivyInterp(dep)))
            s"$objName.$depName"
          }
        }
      }

      model.getDependencies.asScala.foreach { dep =>
        val id = Id(dep)
        dep.getScope match {
          case "compile" if packages.isDefinedAt(id) =>
            compileModuleDeps += packages(id)
          case "compile" =>
            compileIvyDeps += ivyDep(dep)
          case "provided" if packages.isDefinedAt(id) =>
            providedModuleDeps += packages(id)
          case "provided" =>
            providedIvyDeps += ivyDep(dep)
          case "runtime" if packages.isDefinedAt(id) =>
            runtimeModuleDeps += packages(id)
          case "runtime" =>
            runtimeIvyDeps += ivyDep(dep)
          case "test" if packages.isDefinedAt(id) =>
            testModuleDeps += packages(id)
          case "test" =>
            testIvyDeps += ivyDep(dep)
          case scope =>
            println(s"skipping dependency $id with $scope scope")
        }
        // Maven module can be tests only
        if (notPom && testModule.isEmpty) testModule = Option(dep.getGroupId match {
          case "junit" => "TestModule.Junit4"
          case "org.junit.jupiter" => "TestModule.Junit5"
          case "org.testng" => "TestModule.TestNg"
          case _ => null
        })
      }

      val companions = cfg.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
        SortedMap((name, SortedMap(namedIvyDeps.result() *)))
      )

      (
        companions,
        Scoped(compileIvyDeps.result(), compileModuleDeps.result()),
        Scoped(providedIvyDeps.result(), providedModuleDeps.result()),
        Scoped(runtimeIvyDeps.result(), runtimeModuleDeps.result()),
        Scoped(testIvyDeps.result(), testModuleDeps.result()),
        testModule
      )
    }
  }

  private def metadata(model: Model, cfg: BuildGenConfig): String = {
    val description = escape(model.getDescription)
    val organization = escape(model.getGroupId)
    val url = escape(model.getUrl)
    val licenses = model.getLicenses.iterator().asScala.map { license =>
      val id = escape(license.getName)
      val name = id
      val url = escape(license.getUrl)
      val isOsiApproved = false
      val isFsfLibre = false
      val distribution = "\"repo\""

      s"License($id, $name, $url, $isOsiApproved, $isFsfLibre, $distribution)"
    }.mkString("Seq(", ", ", ")")
    val versionControl = Option(model.getScm).fold(Seq.empty[String]) { scm =>
      val repo = escapeOption(scm.getUrl)
      val conn = escapeOption(scm.getConnection)
      val devConn = escapeOption(scm.getDeveloperConnection)
      val tag = escapeOption(scm.getTag)

      Seq(repo, conn, devConn, tag)
    }.mkString("VersionControl(", ", ", ")")
    val developers = model.getDevelopers.iterator().asScala.map { dev =>
      val id = escape(dev.getId)
      val name = escape(dev.getName)
      val url = escape(dev.getUrl)
      val org = escapeOption(dev.getOrganization)
      val orgUrl = escapeOption(dev.getOrganizationUrl)

      s"Developer($id, $name, $url, $org, $orgUrl)"
    }.mkString("Seq(", ", ", ")")
    val publishVersion = escape(model.getVersion)
    val publishProperties =
      if (cfg.publishProperties.value) {
        val props = model.getProperties
        props.stringPropertyNames().iterator().asScala
          .map(key => s"(\"$key\", ${escape(props.getProperty(key))})")
      } else Seq.empty

    val pomSettings =
      s"override def pomSettings = PomSettings($description, $organization, $url, $licenses, $versionControl, $developers)"
    val publishVersionSetting =
      s"override def publishVersion = $publishVersion"
    val publishPropertiesSetting =
      optional(
        "override def publishProperties = super.publishProperties() ++ Map",
        publishProperties
      )

    s"""$pomSettings
       |
       |$publishVersionSetting
       |
       |$publishPropertiesSetting""".stripMargin
  }

  private def escapes(c: Char): Boolean =
    (c: @annotation.switch) match {
      case '\r' | '\n' | '"' => true
      case _ => false
    }

  private def escape(value: String): String =
    if (null == value) "\"\""
    else if (value.exists(escapes)) s"\"\"\"$value\"\"\".stripMargin"
    else s"\"$value\""

  private def escapeOption(value: String): String =
    if (null == value) "None" else s"Some(${escape(value)})"

  private def optional(start: String, value: String): String =
    if (null == value) ""
    else s"$start$value"

  private def optional(construct: String, args: IterableOnce[String]): String =
    optional(construct + "(", args, ",", ")")

  private def optional(
      start: String,
      vals: IterableOnce[String],
      sep: String,
      end: String
  ): String = {
    val itr = vals.iterator
    if (itr.isEmpty) ""
    else itr.mkString(start, sep, end)
  }

  private def resources(relPaths: IterableOnce[os.RelPath]): String = {
    val itr = relPaths.iterator
    if (itr.isEmpty) ""
    else
      itr
        .map(rel => s"PathRef(millSourcePath / \"$rel\")")
        .mkString(s"override def resources = Task.Sources { super.resources() ++ Seq(", ", ", ") }")
  }
}

@main
@mill.api.internal
case class BuildGenConfig(
    @arg(doc = "name of generated base module trait defining project metadata settings")
    baseModule: Option[String] = None,
    @arg(doc = "name of generated nested test module")
    testModule: String = "test",
    @arg(doc = "name of generated companion object defining constants for dependencies")
    depsObject: Option[String] = None,
    @arg(doc = "capture properties defined in pom.xml for publishing")
    publishProperties: Flag = Flag(),
    @arg(doc = "merge build files generated for a multi-module build")
    merge: Flag = Flag(),
    @arg(doc = "use cache for Maven repository system")
    cacheRepository: Flag = Flag(),
    @arg(doc = "process Maven plugin executions and configurations")
    processPlugins: Flag = Flag()
) extends ModelerConfig
