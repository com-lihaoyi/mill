import $meta._
import mill._, scalalib._

object millbuild extends MillBuildRootModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:${constant.MetaConstant.scalatagsVersion}")

  def generatedSources = task {
    os.write(
      task.dest / "Constant.scala",
      s"""package constant
         |object Constant{
         |  def scalatagsVersion = "${constant.MetaConstant.scalatagsVersion}"
         |}
         |""".stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(task.dest / "Constant.scala"))
  }
}