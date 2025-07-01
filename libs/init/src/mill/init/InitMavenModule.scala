package mill.init

import mill.T
import mill.api.{Discover, ExternalModule}
import mill.scalalib.Dep

@mill.api.experimental
private[mill] object InitMavenModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover = Discover[this.type]

  override def buildGenDeps = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-libs-init-maven")
  )

  def buildGenMainClass: T[String] = "mill.main.maven.MavenBuildGenMain"
}
