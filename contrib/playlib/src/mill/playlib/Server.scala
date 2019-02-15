package mill
package playlib

import mill.scalalib._

private[playlib] trait Server extends ScalaModule with Version {
  def playServerProvider = T {
    ivy"com.typesafe.play::play-akka-http-server:${playVersion()}"
  }

  override def runIvyDeps = T {
    super.runIvyDeps() ++ Agg(playServerProvider())
  }

  override def mainClass = T {
    Some("play.core.server.ProdServerStart")
  }
}




