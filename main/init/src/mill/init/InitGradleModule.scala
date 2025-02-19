package mill.init

import mill.T
import mill.api.{PathRef}
import mill.define.{Discover, ExternalModule}

@mill.api.experimental
object InitGradleModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover = Discover[this.type]

  def buildGenClasspath: T[Seq[PathRef]] = BuildGenModule.millModule("mill-main-init-gradle")

  def buildGenMainClass: T[String] = "mill.main.gradle.GradleBuildGenMain"
}
