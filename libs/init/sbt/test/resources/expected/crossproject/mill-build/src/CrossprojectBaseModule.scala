package millbuild

import mill.api.*
import mill.api.opt.*
import mill.javalib.*
import mill.javalib.publish.*
import mill.scalalib.*

trait CrossprojectBaseModule
    extends SbtPlatformModule with PublishModule with SbtModule {

  def mvnDeps = super.mvnDeps() ++ Seq(Deps.upickle)

  def scalaVersion = "2.13.14"

  def publishVersion = "0.1.0-SNAPSHOT"

}
