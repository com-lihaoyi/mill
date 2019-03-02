package mill
package playlib

import mill.scalalib._

import api.Versions

trait PlayApiModule extends Dependencies with Router with Server{
  trait PlayTests extends super.Tests{
    def testFrameworks = Seq("org.scalatest.tools.Framework")
    override def ivyDeps = T{
      playMinorVersion() match {
        case Versions.PLAY_2_6=>
          Agg(ivy"org.scalatestplus.play::scalatestplus-play::3.1.2")
        case Versions.PLAY_2_7=>
          Agg(ivy"org.scalatestplus.play::scalatestplus-play::4.0.1")
      }
    }
    override def sources = T.sources{ millSourcePath }
  }

  def start(args: String*) = T.command{ run(args:_*) }

}
trait PlayModule extends PlayApiModule with Static with Twirl
