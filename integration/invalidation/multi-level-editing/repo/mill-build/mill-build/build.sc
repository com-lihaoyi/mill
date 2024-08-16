// build.sc
import mill._, scalalib._

object millbuild extends MillBuildRootModule {
  def generatedSources = Task {
    os.write(
      Task.dest / "MetaConstant.scala",
      """package constant
        |object MetaConstant{
        |  def scalatagsVersion = "0.8.2"
        |}
        |""".stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(Task.dest / "MetaConstant.scala"))
  }
}
