package millbuild

import mill.javalib._
import mill.javalib.publish._
import mill.scalalib._

trait CrossprojectCrossVersionBaseModule
    extends PublishModule with CrossScalaModule {

  def mvnDeps = super.mvnDeps() ++ Seq(Deps.upickle)

  def scalacOptions = super.scalacOptions() ++ Seq("-deprecation") ++
    (scalaVersion() match {
      case "2.12.20" => Seq(
          "-Xlint:_,-unused",
          "-Ywarn-numeric-widen",
          "-Ywarn-unused:_,-nowarn,-privates"
        )
      case "2.13.14" => Seq("-Xlint:_,-unused", "-Wnumeric-widen", "-Wunused")
      case "3.7.1"   => Seq("-Wunused")
      case _         => Nil
    })

  def publishVersion = "0.1.0-SNAPSHOT"

}
