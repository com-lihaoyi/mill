package mill.main.gradle

import mainargs.ParserForClass
import mill.main.buildgen.*
import mill.main.gradle.BuildInfo.exportpluginAssemblyResource
import mill.util.{CoursierConfig, Jvm}
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import pprint.Util.literalize

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.util.Using

/**
 * Application that generates Mill build files for a Gradle project. This is achieved by using a
 * custom plugin that maps each Gradle `Project` to a module with configurations extracted using the
 * Gradle Tooling API.
 */
object GradleBuildGenMain {

  /**
   * @see [[GradleBuildGenArgs Command line arguments]]
   */
  def main(args: Array[String]): Unit = {
    val args0 = summon[ParserForClass[GradleBuildGenArgs]].constructOrExit(args.toSeq)
    import args0.*

    println("converting Gradle build")

    val gradleWrapperProperties = {
      val properties = new Properties()
      val file = os.pwd / "gradle/wrapper/gradle-wrapper.properties"
      if (os.isFile(file)) Using.resource(os.read.inputStream(file))(properties.load)
      properties
    }

    val packages = {
      val connector = GradleConnector.newConnector() match {
        case conn: DefaultGradleConnector =>
          conn.daemonMaxIdleTime(1, TimeUnit.SECONDS)
          conn
        case conn => conn
      }
      val gradleJavaHome =
        Jvm.resolveJavaHome(id = gradleJvmId, config = CoursierConfig.default()).get
      val exportPluginJar = Using.resource(
        GradleBuildGenMain.getClass.getResourceAsStream(exportpluginAssemblyResource)
      )(os.temp(_, suffix = ".jar"))
      try Using.resource(connector.forProjectDirectory(os.pwd.toIO).connect()) { connection =>
          val initScript = os.temp(
            s"""initscript {
               |    dependencies {
               |      classpath files(${literalize(exportPluginJar.toString())})
               |    }
               |}
               |rootProject {
               |    apply plugin: mill.main.gradle.GradleBuildModelPlugin
               |}
               |""".stripMargin,
            suffix = ".gradle"
          )
          val modelBuilder = connection.model(classOf[GradleBuildModel])
            .setJavaHome(gradleJavaHome.toIO)
            .addArguments("--init-script", initScript.toString)
            .setStandardOutput(System.out)
          val model = modelBuilder.get

          upickle.default.read[Seq[ModuleRepr]](model.getModulesJson).map(Tree(_))
        }
      finally connector.disconnect()
    }

    val gradleJvmArgs = Option(gradleWrapperProperties.getProperty("org.gradle.jvmargs"))
      .fold(Nil)(_.trim.split("\\s+").toSeq)

    var build = BuildRepr.fill(packages).copy(millJvmOpts = gradleJvmArgs)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withMetaBuild
    BuildWriter(build).writeFiles()
  }
}

@mainargs.arg
case class GradleBuildGenArgs(
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "disable generating meta-build files")
    noMeta: mainargs.Flag,
    @mainargs.arg(doc = "JDK to use to run the Gradle daemon")
    // We use the system JDK, instead of the one used by Mill, since it may be used to configure
    // the Java toolchain for the build.
    gradleJvmId: String = "system"
)
object GradleBuildGenArgs {
  given ParserForClass[GradleBuildGenArgs] = ParserForClass.apply
}
