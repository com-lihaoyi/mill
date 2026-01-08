package mill.main.gradle

import mill.main.buildgen.*
import mill.main.gradle.BuildInfo.exportpluginAssemblyResource
import mill.util.Jvm
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import pprint.Util.literalize

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.util.Using

object GradleBuildGenMain {

  def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args.toSeq)

  @mainargs.main(doc = "Generates Mill build files that are derived from a Gradle build.")
  def init(
      @mainargs.arg(doc = "Coursier ID for the JVM to run Gradle")
      gradleJvmId: String = "system",
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "Coursier JVM ID to assign to mill-jvm-version key in the build header")
      millJvmId: Option[String]
  ): Unit = {
    println("converting Gradle build")

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
      try Using.resource(gradleConnector.forProjectDirectory(os.pwd.toIO).connect) { connection =>
          val model = connection.model(classOf[BuildModel])
            .addArguments("--init-script", initScript.toString)
            .setJavaHome(Jvm.resolveJavaHome(gradleJvmId).get.toIO)
            .setStandardOutput(System.out).get
          upickle.default.read[Seq[PackageSpec]](model.asJson)
        }
      finally gradleConnector.disconnect()
    packages = normalizeBuild(packages)

    val millJvmOpts = {
      val properties = new Properties()
      val file = os.pwd / "gradle/wrapper/gradle-wrapper.properties"
      if (os.isFile(file)) Using.resource(os.read.inputStream(file))(properties.load)
      val prop = properties.getProperty("org.gradle.jvmargs")
      if (prop == null) Nil else prop.trim.split("\\s").toSeq
    }
    BuildGen.writeBuildFiles(packages, merge.value, millJvmId, millJvmOpts)
  }

  private def normalizeBuild(packages: Seq[PackageSpec]) = {
    val moduleLookup = packages.flatMap(_.modulesBySegments).toMap
    packages.map(pkg =>
      pkg.copy(module = pkg.module.recMap { module =>
        var module0 = module
        if (module0.supertypes.contains("PublishModule")) {
          val (bomModuleDeps, bomModuleRefs) = module0.bomModuleDeps.base.partition { dep =>
            moduleLookup(dep.segments ++ dep.childSegment).supertypes.contains("PublishModule")
          }
          if (bomModuleRefs.nonEmpty) {
            module0 = module0.copy(
              bomMvnDeps = module0.bomMvnDeps.copy(appendRefs = bomModuleRefs),
              depManagement = module0.depManagement.copy(appendRefs = bomModuleRefs),
              bomModuleDeps = bomModuleDeps
            )
          }
        }
        module0
      })
    )
  }
}
