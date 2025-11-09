package millbuild

import mill.api.*
import mill.api.opt.*
import mill.javalib.*
import mill.javalib.publish.*
import mill.scalalib.*

trait SbtMultiProjectExampleBaseModule extends PublishModule with SbtModule {

  def scalaVersion = "2.12.3"

  def scalacOptions = super.scalacOptions() ++ Opts(
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

  def repositories = super.repositories() ++ Seq(
    "https://oss.sonatype.org/service/local/repositories/releases/content/",
    "https://oss.sonatype.org/content/repositories/snapshots"
  )

}
