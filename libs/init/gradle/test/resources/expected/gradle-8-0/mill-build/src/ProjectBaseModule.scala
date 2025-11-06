package millbuild

import mill.javalib.*

trait ProjectBaseModule extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Opts("-source", "11", "-target", "11")

  def jvmId = "zulu:11"

}
