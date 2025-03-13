package mill.init

import mill.define.{Discover, ExternalModule}

object InitSbtModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover = Discover[this.type]

  def buildGenClasspath = BuildGenModule.millModule("mill-main-init-sbt")

  def buildGenMainClass = "mill.main.sbt.SbtBuildGenMain"
}
