package millbuild

import mill.javalib._
import mill.javalib.publish._
import mill.scalalib._

trait SbtMultiProjectExampleBaseModule extends PublishModule with SbtModule {

  def scalaVersion = "2.12.3"

  def scalacOptions = super.scalacOptions() ++ Seq(
    "-unchecked",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-deprecation"
  )

  def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(Deps.wartremover)

  def publishVersion = "0.1.0-SNAPSHOT"

}
