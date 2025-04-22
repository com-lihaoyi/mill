package mill.init

import mill.T
import mill.define.{Discover, ExternalModule}
import mill.scalalib.Dep

@mill.api.experimental
object InitGradleModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover = Discover[this.type]

  override def buildGenDeps = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-libs-init-gradle")
  )

  def buildGenMainClass: T[String] = "mill.main.gradle.GradleBuildGenMain"
}
