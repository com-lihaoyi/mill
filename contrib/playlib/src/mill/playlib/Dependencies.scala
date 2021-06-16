package mill.playlib

import mill.{Agg, T}
import mill.scalalib._

private[playlib] trait Dependencies extends ScalaModule with Version {
  def core = T { component("play") }
  def guice = T { component("play-guice") }
  def server = T { component("play-server") }
  def logback = T { component("play-logback") }
  def evolutions = T { component("play-jdbc-evolutions") }
  def jdbc = T { component("play-jdbc") }
  def filters = T { component("filters-helpers") }
  def ws = T { component("play-ahc-ws") }
  def caffeine = T { component("play-caffeine-cache") }

  override def ivyDeps = T {
    super.ivyDeps() ++ Agg(
      core(),
      guice(),
      server(),
      logback()
    )
  }
}
