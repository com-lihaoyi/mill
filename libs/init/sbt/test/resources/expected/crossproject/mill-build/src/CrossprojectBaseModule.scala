package millbuild

import mill.javalib.*
import mill.javalib.publish.*
import mill.scalalib.*

trait CrossprojectBaseModule extends PublishModule with SbtPlatformModule {

  def scalaVersion = "2.13.14"

  def mvnDeps = super.mvnDeps() ++ Seq(Deps.upickle)

  def jvmId = "zulu:11"

  def publishVersion = "0.1.0-SNAPSHOT"

}
