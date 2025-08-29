package mill.main.gradle

import mainargs.ParserForClass
import mill.main.buildgen.*
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import pprint.Util.literalize

import java.util.concurrent.TimeUnit
import scala.util.Using

/**
 * Converts a Gradle build to Mill by selecting module configurations using a custom plugin.
 * @see [[https://docs.gradle.org/current/userguide/compatibility.html#java_runtime Gradle JDK compatibility]]
 * @see [[GradleBuildGenArgs Command line arguments]]
 */
object GradleBuildGenMain {

  def main(args: Array[String]): Unit = {
    val args0 = ParserForClass[GradleBuildGenArgs].constructOrExit(args.toSeq)
    println("converting Gradle build")
    import args0.{getClass as _, *}

    val jar = Using.resource(
      getClass.getResourceAsStream(BuildInfo.exportpluginAssemblyResource)
    )(os.temp(_, suffix = ".jar"))
    val script = os.temp(
      s"""initscript {
         |    dependencies {
         |      classpath files(${literalize(jar.toString())})
         |    }
         |}
         |rootProject {
         |    apply plugin: mill.main.gradle.ExportGradleBuildPlugin
         |}
         |""".stripMargin,
      suffix = ".gradle"
    )
    val packages = {
      val connector = GradleConnector.newConnector() match {
        case conn: DefaultGradleConnector =>
          conn.daemonMaxIdleTime(1, TimeUnit.SECONDS)
          conn
        case conn => conn
      }
      try
        val connection = connector.forProjectDirectory(os.pwd.toIO).connect()
        connection.action()
        try
          upickle.default.read[Seq[ModuleRepr]](
            connection.model(classOf[ExportGradleBuildModel])
              .addArguments("--init-script", script.toString())
              .addJvmArguments(s"-Dmill.init.test.module.name=$testModule")
              .setStandardOutput(System.out)
              .get().getModulesJson
          ).map(Tree(_))
        finally connection.close()
      finally connector.disconnect()
    }

    var build = BuildRepr.fill(packages)
    if (merge.value) build = BuildRepr.merged(build)

    val writer = if (noMetaBuild.value) BuildWriter(build)
    else
      val (build0, metaBuild) = MetaBuildRepr.of(build)
      BuildWriter(build0, Some(metaBuild))
    writer.writeFiles()
  }
}

@mainargs.arg
case class GradleBuildGenArgs(
    @mainargs.arg(doc = "name of generated test module")
    testModule: String = "test",
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "disables generating meta-build")
    noMetaBuild: mainargs.Flag
)
