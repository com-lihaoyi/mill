package millbuild

import mill.api.*
import mill.api.opt.*
import mill.javalib.*
import mill.javalib.publish.*
import mill.scalalib.*

trait CrossprojectCrossVersionBaseModule
    extends PublishModule with CrossScalaModule {

  def mvnDeps = super.mvnDeps() ++ Seq(Deps.upickle)

  def scalacOptions = super.scalacOptions() ++ Opts("-deprecation") ++
    (scalaVersion() match {
      case "2.12.20" => Opts(
          "-Xlint:_,-unused",
          "-Ywarn-numeric-widen",
          "-Ywarn-unused:_,-nowarn,-privates"
        )
      case "2.13.14" => Opts("-Xlint:_,-unused", "-Wnumeric-widen", "-Wunused")
      case "3.7.1"   => Opts("-Wunused")
      case _         => Opts()
    })

  def publishVersion = "0.1.0-SNAPSHOT"

}
