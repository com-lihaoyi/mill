package mill.init

import mill.T
import mill.api.{Loose, PathRef}
import mill.define.{Discover, ExternalModule}

@mill.api.experimental
object InitGradleModule extends ExternalModule with BuildGenModule {

  def millDiscover = Discover[this.type]

  def buildGenClasspath: T[Loose.Agg[PathRef]] = BuildGenModule.millModule("mill-main-init-gradle")

  def buildGenMainClass: T[String] = "mill.main.gradle.GradleBuildGenMain"
}
