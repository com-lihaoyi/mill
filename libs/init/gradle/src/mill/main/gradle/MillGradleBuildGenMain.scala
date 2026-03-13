package mill.main.gradle

import mill.main.buildgen.*
import mill.main.buildgen.ModuleSpec.ModuleDep
import mill.main.gradle.BuildInfo.exportpluginAssemblyResource
import mill.util.Jvm
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import pprint.Util.literalize

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.util.Using

object MillGradleBuildGenMain {

  def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args.toSeq)

  @mainargs.main(doc = "Generates Mill build files that are derived from a Gradle build.")
  def init(
      @mainargs.arg(doc = "Coursier ID for the JVM to run Gradle")
      gradleJvmId: String = "system",
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag,
      @mainargs.arg(doc = "Coursier JVM ID to assign to mill-jvm-version key in the build header")
      millJvmId: Option[String],
      @mainargs.arg(doc = "Generate declarative (YAML) or programmable (Scala) build files")
      declarative: Boolean = true,
      @mainargs.arg(doc =
        "The Gradle project directory to migrate. Default is the current working directory."
      )
      projectDir: String = "."
  ): Unit = {
    println("converting Gradle build")

    val gradleWorkspace = os.Path.expandUser(projectDir, os.pwd)
    val millWorkspace = os.pwd

    val exportPluginJar = Using.resource(
      getClass.getResourceAsStream(exportpluginAssemblyResource)
    )(os.temp(_, suffix = ".jar"))
    val initScript = os.temp(
      s"""initscript {
         |    dependencies {
         |      classpath files(${literalize(exportPluginJar.toString)})
         |    }
         |}
         |rootProject {
         |    apply plugin: mill.main.gradle.BuildModelPlugin
         |}
         |""".stripMargin,
      suffix = ".gradle"
    )
    val gradleConnector = GradleConnector.newConnector match {
      case conn: DefaultGradleConnector =>
        conn.daemonMaxIdleTime(1, TimeUnit.SECONDS)
        conn
      case conn => conn
    }
    var packages =
      try Using.resource(gradleConnector.forProjectDirectory(gradleWorkspace.toIO).connect) {
          connection =>
            val model = connection.model(classOf[BuildModel])
              .addArguments("--init-script", initScript.toString)
              .setJavaHome(Jvm.resolveJavaHome(gradleJvmId).get.toIO)
              .setStandardOutput(System.out).get
            upickle.default.read[Seq[PackageSpec]](model.asJson)
        }
      finally gradleConnector.disconnect()
    packages = normalizePackages(packages)

    val millJvmOpts = {
      val properties = new Properties()
      val file = gradleWorkspace / "gradle/wrapper/gradle-wrapper.properties"
      if (os.isFile(file)) Using.resource(os.read.inputStream(file))(properties.load)
      val prop = properties.getProperty("org.gradle.jvmargs")
      if (prop == null) Nil else prop.trim.split("\\s").toSeq
    }

    val build = BuildSpec(packages)
    if (!noMeta.value) {
      if (!declarative) {
        build.deriveDepNames()
      }
      build.deriveBaseModule("MavenModule" -> "MavenTests")
    }
    build.writeFiles(
      declarative = declarative,
      merge = merge.value,
      workspace = millWorkspace,
      millJvmVersion = millJvmId,
      millJvmOpts = millJvmOpts
    )
  }

  private def normalizePackages(packages: Seq[PackageSpec]) = {
    val moduleLookup = packages.flatMap(_.modulesBySegments).toMap
      .compose[ModuleDep](dep => dep.segments ++ dep.childSegment)
    packages.map(pkg =>
      pkg.copy(module = pkg.module.recMap { module =>
        var module0 = module
        if (module0.isPublishModule) {
          val (managedBomModules, bomModuleDeps) = module0.bomModuleDeps.base.partitionMap { dep =>
            val bomModule = moduleLookup(dep)
            Either.cond(bomModule.isPublishModule, dep, bomModule)
          }
          if (managedBomModules.nonEmpty) {
            // Replace references to managed BOM modules
            module0 = module0.copy(
              bomMvnDeps = module0.bomMvnDeps.copy(base =
                module0.bomMvnDeps.base ++ managedBomModules.flatMap(_.bomMvnDeps.base)
              ),
              depManagement = module0.depManagement.copy(base =
                module0.depManagement.base ++ managedBomModules.flatMap(_.depManagement.base)
              ),
              bomModuleDeps = bomModuleDeps
            )
          }
        }
        module0
      })
    )
  }
}
