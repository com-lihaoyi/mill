// build.sc
import mill._, scalalib._

object millbuild extends MillBuildRootModule {
  def generatedSources = T{
    os.write(
      T.dest / "MetaConstant.scala",
      """package constant
        |object MetaConstant{
        |  def scalatagsVersion = "0.8.2"
        |}
        |""".stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(T.dest / "MetaConstant.scala"))
  }
}
