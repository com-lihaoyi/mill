import $meta._
import mill._, scalalib._

object millbuild extends MillBuildRootModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:${constant.MetaConstant.scalatagsVersion}")

  def generatedSources = T {
    os.write(
      T.dest / "Constant.scala",
      s"""package constant
         |object Constant{
         |  def scalatagsVersion = "${constant.MetaConstant.scalatagsVersion}"
         |}
         |""".stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(T.dest / "Constant.scala"))
  }
}