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
 * **Limitations**
 *  - When `useJUnitPlatform()` is used, the `junit-platform-launcher` dependency version is
 *  auto-selected by Gradle and not available during conversion. See
 *  [[https://discuss.gradle.org/t/why-do-gradle-docs-specify-junit-platform-launcher-for-junit-5-tests/46286]].
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
