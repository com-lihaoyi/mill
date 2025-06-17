package mill.init

import mill.T
import mill.define.{Discover, ExternalModule, Task}
import mill.scalalib.Dep

@mill.api.experimental
object InitGradleModule extends ExternalModule with BuildGenModule {

  lazy val millDiscover: Discover = Discover[this.type]

  override def buildGenDeps: Task.Simple[Seq[Dep]] = super.buildGenDeps() ++ Seq(
    Dep.millProjectModule("mill-main-init-gradle")
  )

  def buildGenMainClass: T[String] = "mill.main.gradle.GradleBuildGenMain"
}
