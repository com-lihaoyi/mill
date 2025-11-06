package millbuild

import mill.api.*
import mill.api.opts.*
import mill.javalib.*

trait ProjectBaseModule extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Opts("-source", "11", "-target", "11")

  def jvmId = "zulu:11"

}
