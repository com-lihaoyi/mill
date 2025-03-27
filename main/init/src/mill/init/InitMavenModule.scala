package mill.init

import mill.T
import mill.api.PathRef
import mill.define.{Discover, ExternalModule}
import mill.scalalib.Dep

@mill.api.experimental
object InitMavenModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover = Discover[this.type]

  override def buildGenDeps = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-main-init-maven")
  )

  def buildGenMainClass: T[String] = "mill.main.maven.MavenBuildGenMain"
}
