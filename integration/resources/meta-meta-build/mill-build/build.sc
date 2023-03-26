import mill._, scalalib._

object millbuild extends runner.MillBuildModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:${constant.Constant.scalatagsVersion}")
}