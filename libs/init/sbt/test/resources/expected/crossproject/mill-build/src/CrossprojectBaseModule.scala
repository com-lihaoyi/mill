package millbuild

import mill.javalib._
import mill.javalib.publish._
import mill.scalalib._

trait CrossprojectBaseModule
    extends PublishModule with SbtPlatformModule with SbtModule {

  def scalaVersion = "2.13.14"

  def mvnDeps = super.mvnDeps() ++ Seq(Deps.upickle)

  def publishVersion = "0.1.0-SNAPSHOT"

}
