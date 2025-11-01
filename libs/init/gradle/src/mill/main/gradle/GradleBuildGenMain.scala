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

/**
 * Application that imports a Gradle build to Mill.
 */
object GradleBuildGenMain {

  def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args.toSeq)

  @mainargs.main(doc = "Imports a Gradle build located in the current working directory.")
  def runImport(
      @mainargs.arg(doc = "merge generated build files")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag
  ): Unit = {
    println("converting Gradle build")

    val gradleWrapperProperties = {
      val properties = new Properties()
      val file = os.pwd / "gradle/wrapper/gradle-wrapper.properties"
      if (os.isFile(file)) Using.resource(os.read.inputStream(file))(properties.load)
      properties
    }
    val gradleJvmArgs = Option(gradleWrapperProperties.getProperty("org.gradle.jvmargs"))
      .fold(Nil)(_.trim.split("\\s").toSeq)

    val gradleJavaHome = Jvm.resolveJavaHome("system").get
    val exportPluginJar = Using.resource(
      GradleBuildGenMain.getClass.getResourceAsStream(exportpluginAssemblyResource)
    )(os.temp(_, suffix = ".jar"))
    val gradleConnector = GradleConnector.newConnector() match {
      case conn: DefaultGradleConnector =>
        conn.daemonMaxIdleTime(1, TimeUnit.SECONDS)
        conn
      case conn => conn
    }
    val packages =
      try {
        Using.resource(gradleConnector.forProjectDirectory(os.pwd.toIO).connect()) { connection =>
          val initScript = os.temp(
            s"""initscript {
               |    dependencies {
               |      classpath files(${literalize(exportPluginJar.toString())})
               |    }
               |}
               |rootProject {
               |    apply plugin: mill.main.gradle.BuildModelPlugin
               |}
               |""".stripMargin,
            suffix = ".gradle"
          )
          val modelBuilder = connection.model(classOf[BuildModel])
            .setJavaHome(gradleJavaHome.toIO)
            .addArguments("--init-script", initScript.toString)
          println("connecting to Gradle daemon")
          val model = modelBuilder.get
          upickle.default.read[Seq[PackageSpec]](model.asJson)
        }
      } finally gradleConnector.disconnect()

    var build = BuildSpec.fill(packages).copy(millJvmOpts = gradleJvmArgs)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withDefaultMetaBuild
    BuildWriter(build).writeFiles()
  }
}
