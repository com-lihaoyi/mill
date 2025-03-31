package mill.init

import mill.T
import mill.define.{Discover, ExternalModule}
import mill.scalalib.Dep
import mill.define.Target

@mill.api.experimental
object InitSbtModule extends ExternalModule with BuildGenModule {
  lazy val millDiscover = Discover[this.type]

  override def buildGenDeps: Target[Seq[Dep]] = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-main-init-sbt")
  )

  def buildGenMainClass: T[String] = "mill.main.sbt.SbtBuildGenMain"
}
