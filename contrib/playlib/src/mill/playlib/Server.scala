package mill.playlib

import mill.scalalib._
import mill.{Agg, T}

private[playlib] trait Server extends ScalaModule with Version {

  def nettyServer = T { component("play-netty-server") }

  def akkaHttpServer = T { component("play-akka-http-server") }

  def pekkoHttpServer = T { component("play-pekko-http-server") }

  def playServerProvider = T {
    if (playVersion().startsWith("2."))
      akkaHttpServer()
    else
      pekkoHttpServer()
  }

  override def runIvyDeps = T {
    super.runIvyDeps() ++ Agg(playServerProvider())
  }

  override def mainClass = T { Some("play.core.server.ProdServerStart") }
}
