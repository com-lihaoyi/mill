package mill
package playlib

import mill.scalalib._

private[playlib] trait Server extends ScalaModule with Version {

  def nettyServer = T { component("play-netty-server") }

  def akkaHttpServer = T { component("play-akka-http-server") }

  def playServerProvider = T { akkaHttpServer() }


  override def runIvyDeps = T {
    super.runIvyDeps() ++ Agg(playServerProvider())
  }

  override def mainClass = T { Some("play.core.server.ProdServerStart") }
}




