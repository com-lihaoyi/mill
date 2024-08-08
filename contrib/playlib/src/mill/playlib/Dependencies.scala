package mill.playlib

import mill.{Agg, T, task}
import mill.scalalib._

private[playlib] trait Dependencies extends ScalaModule with Version {
  def core = task { component("play") }
  def guice = task { component("play-guice") }
  def server = task { component("play-server") }
  def logback = task { component("play-logback") }
  def evolutions = task { component("play-jdbc-evolutions") }
  def jdbc = task { component("play-jdbc") }
  def filters = task { component("filters-helpers") }
  def ws = task { component("play-ahc-ws") }
  def caffeine = task { component("play-caffeine-cache") }

  override def ivyDeps = task {
    super.ivyDeps() ++ Agg(
      core(),
      guice(),
      server(),
      logback()
    )
  }
}
