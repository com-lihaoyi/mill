package millbuild

import mill.javalib._

trait `9.0.0BaseModule` extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Seq("-source", "21", "-target", "21")

  def jvmId = "zulu:21"

}
