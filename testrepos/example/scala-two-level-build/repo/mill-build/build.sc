import mill._, scalalib._

println("Evaluating mill-build/build.sc")
object millbuild extends runner.MillBuildModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")

  def generatedSources = T {
    os.write(
      T.dest / "Constant.scala",
      s"""package constant
         |object Constant{
         |  def scalatagsVersion = "0.8.1"
         |}
         |""".stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(T.dest / "Constant.scala"))
  }
}