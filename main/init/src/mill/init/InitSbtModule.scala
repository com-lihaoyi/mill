package mill.init

import mill.T
import mill.api.{Loose, PathRef}
import mill.define.{Discover, ExternalModule}

@mill.api.experimental
object InitSbtModule extends ExternalModule with BuildGenModule {
  lazy val millDiscover: Discover = Discover[this.type]

  def buildGenClasspath: T[Loose.Agg[PathRef]] = BuildGenModule.millModule("mill-main-init-sbt")

  def buildGenMainClass: T[String] = "mill.main.sbt.SbtBuildGenMain"
}
