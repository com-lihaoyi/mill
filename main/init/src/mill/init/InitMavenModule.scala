package mill.init

import mill.T
import mill.define.{Discover, ExternalModule}
import mill.scalalib.Dep
import mill.define.Target

@mill.api.experimental
object InitMavenModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover: Discover = Discover[this.type]

  override def buildGenDeps: Target[Seq[Dep]] = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-main-init-maven")
  )

  def buildGenMainClass: T[String] = "mill.main.maven.MavenBuildGenMain"
}
