package mill.main.maven

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.build.{BuildCompanion, BuildDefinition, Node}
import mill.runner.FileImportGraph.backtickWrap
import org.apache.maven.model.{Dependency, Model}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.jdk.CollectionConverters.*

/**
 * Converts a Maven build to Mill by generating Mill build file(s) from POM file(s).
 *
 * The generated output should be considered scaffolding and will likely require edits to complete conversion.
 *
 * ===Features===
 *  - converts nested modules
 *  - captures publish metadata
 *  - configures dependencies for scopes:
 *    - compile
 *    - provided
 *    - runtime
 *    - test
 *  - configures testing frameworks:
 *    - JUnit 4
 *    - JUnit 5
 *    - TestNG
 *  - supports Maven plugins:
 *    - org.apache.maven.plugins:maven-compiler-plugin
 *    - org.codehaus.mojo:build-helper-maven-plugin
 *      - path filters are not supported
 *
 * ===Limitations===
 *  - build extensions are not supported
 *  - build profiles are not supported
 *  - packaging support is limited to
 *    - jar
 *    - pom
 */
@mill.api.internal
object BuildGen {

  def main(args: Array[String]): Unit = {
    implicit val cfg: BuildGenConfig = ParserForClass[BuildGenConfig].constructOrExit(args.toSeq)
    run
  }

  private type MavenNode = Node[Model]
  private type MillNode = Node[BuildDefinition]

  // using @main annotation here, with ParserForMethods in main, causes failure:
  //   No @main methods declared
  // moved doc to BuildGenConfig as a workaround
  private def run(implicit cfg: BuildGenConfig): Unit = {
    val workspace = os.pwd

    println("converting Maven build ...")
    val inputs = {
      val b = Seq.newBuilder[MavenNode]
      val modeler = Modeler()

      def recurse(dirs: Seq[String]): Unit = {
        val model = modeler(workspace / dirs)
        b += Node(dirs, model)
        model.getModules.iterator().asScala
          .map(dirs :+ _)
          .foreach(recurse)
      }

      recurse(Seq.empty)
      b.result()
    }

    val outputs = make(inputs)
    println(s"generated ${outputs.size} Mill build file(s)")

    outputs.foreach { build =>
      val file = build.file
      val source = build.source
      println(s"writing Mill build file to $file ...")
      // overwrite files, if any, from a previous run
      os.write.over(workspace / file, source)
    }

    println("converted Maven build to Mill")
  }

  private def make(inputs: Seq[MavenNode])(implicit
      cfg: BuildGenConfig
  ): Seq[MillNode] = {
    val baseModule = cfg.baseModule
    val noSharePublish = cfg.noSharePublish.value

    val packages = // for resolving moduleDeps
      inputs.iterator
        .map(build => (Id(build.module), build.pkg))
        .toMap

    val baseModuleTypedef = {
      val publishSettings = if (noSharePublish) "" else publish(inputs.head.module)

      s"""trait $baseModule extends MavenModule with PublishModule {
         |$publishSettings
         |}""".stripMargin
    }

    inputs.map { case build @ Node(dirs, model) =>
      val imports = Seq(
        "import mill._",
        "import mill.javalib._",
        "import mill.javalib.publish._"
      ) ++ (
        if (dirs.nonEmpty) Some(s"import $$file.$baseModule")
        else Option.when(packages.size > 1)("import $packages._")
      )

      val typedefs = if (dirs.isEmpty) Seq(baseModuleTypedef) else Seq.empty
      val name = "`package`"
      val supertypes = Seq("RootModule", baseModule)

      val (depsObject, compileDeps, providedDeps, runtimeDeps, test) = Scoped.all(model, packages)
      val body = {
        val packaging = model.getPackaging
        val javacOptions = Plugins.`maven-compiler-plugin`.javacOptions(model)
        val ((compileSources, compileResources), (testSources, testResources)) =
          Plugins.`build-helper-maven-plugin`.added(model)

        val artifactNameSetting = {
          val id = model.getArtifactId
          val name = if (dirs.nonEmpty && dirs.last == id) null else id // skip default
          escapeOptional("override def artifactName = ", name)
        }
        val pomPackagingTypeSetting =
          escapeOptional(s"override def pomPackagingType = ", packaging, "jar")
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
        val javacOptionsSetting =
          optional("override def javacOptions = Seq(\"", javacOptions, "\",\"", "\")")
        val sourcesSetting = sources("sources", compileSources)
        val resourcesSetting = sources("resources", compileResources)
        val depsSettings = compileDeps.settings("ivyDeps", "moduleDeps")
        val compileDepsSettings = providedDeps.settings("compileIvyDeps", "compileModuleDeps")
        val runDepsSettings = runtimeDeps.settings("runIvyDeps", "runModuleDeps")
        val publishSettings = if (noSharePublish) publish(model) else ""
        val testModuleTypedef =
          if ("pom" == packaging) ""
          else {
            val sourcesSetting = sources("sources", testSources)
            val resourcesSetting = sources("resources", testResources)
            test.typedef(sourcesSetting, resourcesSetting)
          }

        s"""$artifactNameSetting
           |$pomPackagingTypeSetting
           |$pomParentProjectSetting
           |$javacOptionsSetting
           |$sourcesSetting
           |$resourcesSetting
           |$depsSettings
           |$compileDepsSettings
           |$runDepsSettings
           |$publishSettings
           |$testModuleTypedef""".stripMargin
      }

      build.copy(module =
        BuildDefinition(imports, typedefs, depsObject.toSeq, name, supertypes, body)
      )
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
         |$moduleDepsSetting
         |""".stripMargin
    }
  }
  private object Scoped {

    private type Compile = Scoped
    private type Provided = Scoped
    private type Runtime = Scoped

    case class Test(ivyDeps: IvyDeps, moduleDeps: ModuleDeps, supertype: Option[String]) {

      def typedef(settings: String*): String =
        if (ivyDeps.isEmpty && moduleDeps.isEmpty) ""
        else {
          val declare = supertype match {
            case Some(module) =>
              s"object test extends MavenTests with $module"
            case None =>
              s"trait Tests extends MavenTests"
          }
          val ivyDepsSetting =
            optional(s"override def ivyDeps = super.ivyDeps() ++ Agg", ivyDeps)
          val moduleDepsSetting =
            optional(s"override def moduleDeps = super.moduleDeps ++ Seq", moduleDeps)

          s"""$declare {
             |$ivyDepsSetting
             |$moduleDepsSetting
             |${settings.mkString(System.lineSeparator())}
             |}""".stripMargin
        }
    }

    def all(model: Model, packages: PartialFunction[Id, Package])(implicit
        cfg: BuildGenConfig
    ): (Option[BuildCompanion], Compile, Provided, Runtime, Test) = {
      val compileIvyDeps = SortedSet.newBuilder[String]
      val providedIvyDeps = SortedSet.newBuilder[String]
      val runtimeIvyDeps = SortedSet.newBuilder[String]
      val testIvyDeps = SortedSet.newBuilder[String]
      val compileModuleDeps = SortedSet.newBuilder[String]
      val providedModuleDeps = SortedSet.newBuilder[String]
      val runtimeModuleDeps = SortedSet.newBuilder[String]
      val testModuleDeps = SortedSet.newBuilder[String]
      var testModule = Option.empty[String]

      val ivyInterp: Dependency => IvyInterp = {
        val module = model.getProjectDirectory.getName
        dep => {
          val group = dep.getGroupId
          val id = dep.getArtifactId
          val version = dep.getVersion
          val tpe = dep.getType match {
            case null | "jar" => "" // skip default
            case tpe => s";type=$tpe"
          }
          val classifier = dep.getClassifier match {
            case null => ""
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
            if (testModule.isEmpty) testModule = Option(dep.getGroupId match {
              case "junit" => "TestModule.Junit4"
              case "org.junit.jupiter" => "TestModule.Junit5"
              case "org.testng" => "TestModule.TestNg"
              case _ => null
            })
          case scope =>
            println(s"skipping dependency $id with $scope scope")
        }
      }

      val depsObject = cfg.depsObject.map(BuildCompanion(_, SortedMap(namedIvyDeps.result() *)))

      (
        depsObject,
        Scoped(compileIvyDeps.result(), compileModuleDeps.result()),
        Scoped(providedIvyDeps.result(), providedModuleDeps.result()),
        Scoped(runtimeIvyDeps.result(), runtimeModuleDeps.result()),
        Test(testIvyDeps.result(), testModuleDeps.result(), testModule)
      )
    }
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

  private def escapeOptional(start: String, value: String, skip: String*): String =
    if (null == value || skip.contains(value)) ""
    else s"$start${escape(value)}"

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

  private def publish(model: Model)(implicit cfg: BuildGenConfig): String = {
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
    val version = escape(model.getVersion)
    val properties =
      if (cfg.publishProperties.value) {
        val props = model.getProperties
        props.stringPropertyNames().iterator().asScala
          .map(key => s"(\"$key\", ${escape(props.getProperty(key))})")
      } else Seq.empty

    val pomSettings =
      s"override def pomSettings = PomSettings($description, $organization, $url, $licenses, $versionControl, $developers)"
    val publishVersion =
      s"override def publishVersion = $version"
    val publishProperties =
      optional("override def publishProperties = super.publishProperties() ++ Map", properties)

    s"""$pomSettings
       |$publishVersion
       |$publishProperties
       |""".stripMargin
  }

  private def sources(name: String, values: IterableOnce[String]): String =
    optional(s"override def $name = Task.Sources { super.$name() ++ Seq(", values, ",", ")}")
}

@main
@mill.api.internal
case class BuildGenConfig(
    @arg(doc = "generated base module trait name")
    baseModule: String = "BaseMavenModule",
    @arg(doc = "generated dependencies companion object name")
    depsObject: Option[String] = None,
    @arg(doc = "do not define and share publish settings in base module")
    noSharePublish: Flag = Flag(),
    @arg(doc = "capture and publish properties defined in pom.xml")
    publishProperties: Flag = Flag(),
    @arg(doc = "use cache for Maven repository system")
    cacheRepository: Flag = Flag(),
    @arg(doc = "process Maven plugin executions and configurations")
    processPlugins: Flag = Flag()
) extends ModelerConfig
