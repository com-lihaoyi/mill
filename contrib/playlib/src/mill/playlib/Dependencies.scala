package mill.playlib

import mill.Task
import mill.scalalib.*

private[playlib] trait Dependencies extends ScalaModule with Version {
  def core = Task { component("play")() }
  def guice = Task { component("play-guice")() }
  def server = Task { component("play-server")() }
  def logback = Task { component("play-logback")() }
  def evolutions = Task { component("play-jdbc-evolutions")() }
  def jdbc = Task { component("play-jdbc")() }
  def filters = Task { component("filters-helpers")() }
  def ws = Task { component("play-ahc-ws")() }
  def caffeine = Task { component("play-caffeine-cache")() }

  override def mvnDeps = Task {
    super.mvnDeps() ++ Seq(
      core(),
      guice(),
      server(),
      logback()
    )
  }
}
