package mill.bsp

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.scalalib.bsp.BspUri

package object worker {

  implicit class BspUriSupport(bspUri: BspUri) {
    def buildTargetIdentifier: BuildTargetIdentifier = new BuildTargetIdentifier(bspUri.uri)
  }

  implicit class BuildTargetIdentifierSupport(id: BuildTargetIdentifier) {
    def bspUri: BspUri = BspUri(id.getUri())
  }

}
