package mill.init.maven

import mill.define.{Discover, ExternalModule, TaskModule}
import mill.init.maven.format.*
import mill.{Command, Module, T, Task}
import org.apache.maven.model.{Dependency, Model}

import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.*

/**
 * Provides a [[InitModule.init task]] to generate Mill build files for an existing Maven project.
 */
object InitModule extends ExternalModule with InitModule with TaskModule {

  override def defaultCommandName(): String = "init"

  lazy val millDiscover: Discover = Discover[this.type]
}
trait InitModule extends Module {

  /**
   * Converts a Maven build to Mill automatically.
   *
   * @note The conversion may be incomplete, requiring manual edits to generated Mill build file(s).
   */
  def init(
      @mainargs.arg(doc = "build base module (with shared settings) name")
      baseModule: String = "BaseMavenModule"
  ): Command[Unit] = Task.Command {

    T.log.info("analyzing Maven project ...")

    type ModelPackage = (Model, BuildPackage)
    type BuildPackage = String

    // identify Maven models and corresponding Mill packages
    val (models, modules) = {
      val (models, modules) = (Seq.newBuilder[ModelPackage], Map.newBuilder[ModelId, BuildPackage])
      val reader = PomReader()
      def loop(pomDir: os.Path, pkg: String): Unit = {
        val model = reader(pomDir)
        val modelId = (model.getGroupId, model.getArtifactId, model.getVersion)
        models += ((model, pkg))
        modules += (modelId -> pkg)
        model.getModules.asScala.foreach(dir => loop(pomDir / dir, s"$pkg.`$dir`"))
      }
      loop(millSourcePath, "build")
      (models.result(), modules.result())
    }

    T.log.info(s"found ${modules.size} module(s)")

    // root model
    val model0 = models.head._1
    // identify metadata from root model
    val (baseModuleSource, testModule) = {
      val licenses = model0.getLicenses.iterator().asScala.map { license =>
        val name = Option(license.getName).getOrElse("")
        val url = Option(license.getUrl).getOrElse("")
        s"""License("$name", "$name", "$url", false, false, "repo")"""
      }
      val developers = model0.getDevelopers.iterator().asScala.map { dev =>
        s"""Developer("${
            Option(dev.getId).getOrElse("")
          }", "${
            Option(dev.getName).getOrElse("")
          }", "${
            Option(dev.getUrl).getOrElse("")
          }", Option(${
            Option(dev.getOrganization).fold("null")(escape)
          }), Option(${
            Option(dev.getOrganizationUrl).fold("null")(escape)
          }))"""
      }
      val testModule = model0.getDependencies.iterator().asScala.collectFirst {
        case dep if dep.getGroupId == "junit" => "TestModule.Junit4"
        case dep if dep.getGroupId == "org.junit.jupiter" => "TestModule.Junit5"
      }
      val testFrameworkIvyDeps = testModule.collect {
        case "TestModule.Junit4" => "ivy\"com.novocode:junit-interface:0.11\""
        case "TestModule.Junit5" => "ivy\"com.github.sbt.junit:jupiter-interface:0.13.0\""
      }.toSeq
      val testModuleSource =
        s"""  trait Tests extends ${testModule.fold("MavenTests")(mod => s"$mod with MavenTests")} {
           |
           |    override def defaultCommandName() = "test"
           |${
            if (testFrameworkIvyDeps.isEmpty) ""
            else
              s"""
                 |    override def ivyDeps = super.ivyDeps() ++ ${
                  break.cs.indent(3, "Agg(", testFrameworkIvyDeps, ")")
                }""".stripMargin
          }
           |  }""".stripMargin
      (
        s"""trait $baseModule extends MavenModule with PublishModule {
           |
           |  override def pomSettings = PomSettings(
           |    ${Option(model0.getDescription).fold(escape.empty)(escape.multi)},
           |    "${Option(model0.getOrganization).flatMap(a => Option(a.getName)).getOrElse("")}",
           |    "${Option(model0.getUrl).getOrElse("")}",
           |    ${if (licenses.isEmpty) "Seq()" else break.cs.indent(3, "Seq(", licenses, ")")},
           |    VersionControl(${Option(model0.getScm).fold("),") { scm =>
            s"""
               |      Option(${Option(scm.getUrl).fold("null")(escape)}),
               |      Option(${Option(scm.getConnection).fold("null")(escape)}),
               |      Option(${Option(scm.getDeveloperConnection).fold("null")(escape)}),
               |      Option(${Option(scm.getTag).fold("null")(escape)})
               |    ),""".stripMargin
          }}
           |    ${if (developers.isEmpty) "Seq()" else break.cs.indent(3, "Seq(", developers, ")")}
           |  )
           |
           |  override def publishVersion = "${model0.getVersion}"
           |
           |$testModuleSource
           |}""".stripMargin,
        testModule
      )
    }

    // convert Maven models to Mill build definitions
    val buildFiles = models.map { case (model, pkg) =>
      val isRoot = model eq model0
      val dir = os.Path(model.getPomFile.getParentFile)
      val header =
        s"""package $pkg
           |
           |import mill._
           |import mill.javalib._
           |import mill.javalib.publish._
           |${
            if (isRoot)
              // a magic import is needed to "lift" child modules to the root task namespace
              s"""${
                  if (modules.size > 1)
                    s"""
                       |${"// required to lift child packages to the root task namespace"}
                       |import $$packages._""".stripMargin
                  else ""
                }
                 |
                 |$baseModuleSource""".stripMargin
            else
              s"""
                 |${"// required to access base package in root build file"}
                 |import $$file.$baseModule""".stripMargin
          }
           |
           |object `package` extends RootModule with $baseModule""".stripMargin

      val source = model.getPackaging match {
        case "pom" =>
          s"""$header {
             |
             |  override def artifactName = "${model.getArtifactId}"
             |
             |  override def pomPackagingType = PackagingType.Pom
             |}
             |""".stripMargin

        // this should be limited to jar but having some scaffolding might be useful
        case _ =>
          val javacOptions = plugin.`maven-compiler-plugin`.javacOptions(model)
          val (moduleDeps, ivyDeps, compileIvyDeps, runIvyDeps, testIvyDeps) = deps(model, modules)
          val testSource =
            if (testIvyDeps.isEmpty) ""
            else
              s"""  ${
                  if (testModule.isEmpty) "trait Tests extends super.Tests"
                  else "object test extends Tests"
                } {
                 |
                 |    override def ivyDeps = super.ivyDeps() ++ ${
                  break.cs.indent(3, "Agg(", testIvyDeps, ")")
                }
                 |  }
                 |""".stripMargin

          s"""$header {
             |
             |  override def artifactName = "${model.getArtifactId}"
             |${
              if (javacOptions.isEmpty) ""
              else
                s"""
                   |  override def javacOptions = ${
                    break.cs.indent(2, "Seq(", javacOptions.iterator.map(escape), ")")
                  }
                   |""".stripMargin
            }${
              if (moduleDeps.isEmpty) ""
              else
                s"""
                   |  override def moduleDeps = ${break.cs.indent(2, "Seq(", moduleDeps, ")")}
                   |""".stripMargin
            }${
              if (ivyDeps.isEmpty) ""
              else
                s"""
                   |  override def ivyDeps = ${break.cs.indent(2, "Agg(", ivyDeps, ")")}
                   |""".stripMargin
            }${
              if (compileIvyDeps.isEmpty) ""
              else
                s"""
                   |  override def compileIvyDeps = ${
                    break.cs.indent(2, "Agg(", compileIvyDeps, ")")
                  }
                   |""".stripMargin
            }${
              if (runIvyDeps.isEmpty) ""
              else
                s"""
                   |  override def runIvyDeps = ${break.cs.indent(2, "Agg(", runIvyDeps, ")")}
                   |""".stripMargin
            }
             |
             |$testSource
             |}""".stripMargin
      }

      (dir, source)
    }

    T.log.info(s"generated ${buildFiles.size} Mill build file(s)")

    val (dir, source) +: tail = buildFiles
    val file = dir / "build.mill"
    T.log.info(s"writing build file $file ...")
    os.write(file, source, createFolders = true)
    tail.foreach {
      case (dir, source) =>
        if (source.nonEmpty) {
          val file = dir / "package.mill"
          T.log.info(s"writing build file $file ...")
          os.write(file, source, createFolders = true)
        }
    }
  }

  private type ModelId = (String, String, String)
  private type ModuleDep = String
  private type ModuleDeps = SortedSet[ModuleDep]
  private type InterpIvyDep = String
  private type IvyDeps = SortedSet[InterpIvyDep]
  private type CompileIvyDeps = SortedSet[InterpIvyDep]
  private type RunIvyDeps = SortedSet[InterpIvyDep]
  private type TestIvyDeps = SortedSet[InterpIvyDep]

  private def deps(
      model: Model,
      modules: PartialFunction[ModelId, ModuleDep]
  ): (ModuleDeps, IvyDeps, CompileIvyDeps, RunIvyDeps, TestIvyDeps) = {
    val (moduleDeps, ivyDeps, compileIvyDeps, runIvyDeps, testIvyDeps) = (
      SortedSet.newBuilder[String],
      SortedSet.newBuilder[String],
      SortedSet.newBuilder[String],
      SortedSet.newBuilder[String],
      SortedSet.newBuilder[String]
    )

    model.getDependencies.asScala.foreach { dep =>
      val depId = (dep.getGroupId, dep.getArtifactId, dep.getVersion)
      if (modules.isDefinedAt(depId)) moduleDeps += modules(depId)
      else dep.getScope match {
        case "compile" => ivyDeps += ivy(dep)
        case "provided" => compileIvyDeps += ivy(dep)
        case "runtime" => runIvyDeps += ivy(dep)
        case "test" => testIvyDeps += ivy(dep)
        case _ =>
      }
    }
    (
      moduleDeps.result(),
      ivyDeps.result(),
      compileIvyDeps.result(),
      runIvyDeps.result(),
      testIvyDeps.result()
    )
  }

  private def ivy(dep: Dependency): InterpIvyDep =
    s"ivy\"${
        dep.getGroupId
      }:${
        dep.getArtifactId
      }:${
        dep.getVersion
      }${
        val tpe = dep.getType
        if (tpe == "jar") "" else s";type=$tpe"
      }${
        val classifier = dep.getClassifier
        if (null == classifier) "" else s";classifier=$classifier"
      }${
        dep.getExclusions.iterator.asScala
          .map(x => s";exclude=${x.getGroupId}:${x.getArtifactId}")
          .mkString
      }\""
}
