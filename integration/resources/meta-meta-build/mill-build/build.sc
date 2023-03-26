import mill._, scalalib._

object millbuild extends entrypoint.MillBuildModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:${constant.Constant.scalatagsVersion}")
}