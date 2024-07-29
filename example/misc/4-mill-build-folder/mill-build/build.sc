import mill._, scalalib._

object millbuild extends MillBuildRootModule {
  val scalatagsVersion = "0.13.1"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:$scalatagsVersion")

  def generatedSources = T {
    os.write(
      T.dest / "DepVersions.scala",
      s"""
         |package millbuild
         |object DepVersions{
         |  def scalatagsVersion = "$scalatagsVersion"
         |}
      """.stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(T.dest))
  }
}
