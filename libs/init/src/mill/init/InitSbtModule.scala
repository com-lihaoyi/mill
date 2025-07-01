package mill.init

import mill.T
import mill.api.{Discover, ExternalModule}
import mill.scalalib.Dep

@mill.api.experimental
object InitSbtModule extends ExternalModule with BuildGenModule {
  lazy val millDiscover = Discover[this.type]

  override def buildGenDeps = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-libs-init-sbt")
  )

  def buildGenMainClass: T[String] = "mill.main.sbt.SbtBuildGenMain"
}
