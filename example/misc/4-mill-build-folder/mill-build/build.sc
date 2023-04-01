import mill._, scalalib._

object millbuild extends runner.MillBuildFileModule{
  val scalatagsVersion = "0.8.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:$scalatagsVersion")

  def generatedSources = T {
    os.write(
      T.dest / "DepVersions.scala",
      s"""package millbuild
         |object DepVersions{
         |  def scalatagsVersion = "$scalatagsVersion"
         |}
         |""".stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(T.dest))
  }
}