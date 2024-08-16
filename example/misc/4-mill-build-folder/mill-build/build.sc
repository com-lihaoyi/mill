import mill._, scalalib._

object millbuild extends MillBuildRootModule{
  val scalatagsVersion = "0.12.0"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:$scalatagsVersion")

  def generatedSources = Task {
    os.write(
      Task.dest / "DepVersions.scala",
      s"""
         |package millbuild
         |object DepVersions{
         |  def scalatagsVersion = "$scalatagsVersion"
         |}
      """.stripMargin
    )
    super.generatedSources() ++ Seq(PathRef(Task.dest))
  }
}