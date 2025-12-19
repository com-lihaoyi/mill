package millbuild
import mill.*
import mill.javalib.PublishModule
import mill.javalib.publish.*
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.*
import mill.scalalib.*
import mill.scalanativelib.ScalaNativeModule
import mill.scalanativelib.api.*
trait ProjectBaseModule extends PublishModule, CrossSbtPlatformModule {

  def mvnDeps = Seq(Deps.upickle)

  def scalacOptions = Seq("-deprecation") ++
    (crossScalaVersion match {
      case "2.12.20" => Seq(
          "-Xlint:_,-unused",
          "-Ywarn-numeric-widen",
          "-Ywarn-unused:_,-nowarn,-privates"
        )
      case "2.13.14" => Seq("-Xlint:_,-unused", "-Wnumeric-widen", "-Wunused")
      case "3.7.1"   => Seq("-Wunused")
      case _         => Seq()
    })

  def publishVersion = "0.1.0-SNAPSHOT"

}
