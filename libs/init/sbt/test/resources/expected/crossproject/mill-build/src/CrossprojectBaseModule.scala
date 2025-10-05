package millbuild

import mill.javalib._
import mill.javalib.publish._
import mill.scalalib._

trait CrossprojectBaseModule
    extends SbtPlatformModule with PublishModule with SbtModule {

  def mvnDeps = super.mvnDeps() ++ Seq(Deps.upickle)

  def scalaVersion = "2.13.14"

  def publishVersion = "0.1.0-SNAPSHOT"

}
