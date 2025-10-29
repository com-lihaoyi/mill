package millbuild

import mill.javalib.*

trait ProjectBaseModule extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Seq("-source", "11", "-target", "11")

  def jvmId = "zulu:11"

}
