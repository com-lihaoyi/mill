package millbuild

import mill.javalib.*

trait ProjectBaseModule extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Seq("-source", "21", "-target", "21")

  def jvmId = "zulu:21"

}
