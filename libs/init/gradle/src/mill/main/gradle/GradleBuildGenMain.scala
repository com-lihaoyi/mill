package mill.main.gradle

import mainargs.ParserForClass
import mill.main.buildgen.*
import org.gradle.tooling.GradleConnector
import pprint.Util.literalize

import scala.util.Using

@mainargs.arg
case class GradleBuildGenArgs(
    @mainargs.arg(short = 't')
    testModuleName: String = "test",
    @mainargs.arg(short = 'u')
    unify: mainargs.Flag,
    metaBuild: MetaBuildArgs
)

/**
 * @see [[https://docs.gradle.org/current/userguide/compatibility.html#java_runtime JDK compatibility]]
 * @see [[https://docs.gradle.org/current/userguide/upgrading_version_8.html#test_framework_implementation_dependencies Test auto-dependencies]]
 */
object GradleBuildGenMain {

  def main(args: Array[String]): Unit = {
    val args0 = ParserForClass[GradleBuildGenArgs].constructOrExit(args.toSeq)
    import args0.{getClass as _, *}
    println("converting Gradle build")

    val modules =
      val connector = GradleConnector.newConnector()
      try
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
        val connection = connector.forProjectDirectory(os.pwd.toIO).connect()
        connection.action()
        try
          upickle.default.read[Seq[ModuleRepr]](
            connection.model(classOf[ExportGradleBuildModel])
              .addArguments("--init-script", script.toString())
              .addJvmArguments(s"-Dmill.init.test.module.name=$testModuleName")
              .setStandardOutput(System.out)
              .get().getModulesJson()
          )
        finally connection.close()
      finally connector.disconnect()

    var build = BuildRepr.fill(modules.map(Tree(_)))
    build = build.withMetaBuild(metaBuild)
    if (unify.value) build = build.unified
    BuildWriter(build).writeFiles()
  }
}
