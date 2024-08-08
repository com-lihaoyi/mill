package mill.playlib

import mill.scalalib._
import mill.{Agg, T, task}

private[playlib] trait Server extends ScalaModule with Version {

  def nettyServer = task { component("play-netty-server") }

  def akkaHttpServer = task { component("play-akka-http-server") }

  def pekkoHttpServer = task { component("play-pekko-http-server") }

  def playServerProvider = task {
    if (playVersion().startsWith("2."))
      akkaHttpServer()
    else
      pekkoHttpServer()
  }

  override def runIvyDeps = task {
    super.runIvyDeps() ++ Agg(playServerProvider())
  }

  override def mainClass = task { Some("play.core.server.ProdServerStart") }
}
