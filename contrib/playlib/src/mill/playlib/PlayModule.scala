package mill
package playlib

import mill.define.Target
import mill.scalalib._
import mill.playlib.api.Versions

trait PlayApiModule extends Dependencies with Router with Server{
  trait PlayTests extends super.Tests{
    def testFrameworks = Seq("org.scalatest.tools.Framework")
    override def ivyDeps = T{
      val scalatestPlusPlayVersion = playMinorVersion() match {
        case Versions.PLAY_2_6 => "3.1.2"
        case Versions.PLAY_2_7 => "4.0.3"
        case Versions.PLAY_2_8 => "5.1.0"
      }
      Agg(ivy"org.scalatestplus.play::scalatestplus-play::${scalatestPlusPlayVersion}")
    }
    override def sources = T.sources{ millSourcePath }
  }

  def start(args: String*) = T.command{ run(args:_*) }

}
trait PlayModule extends PlayApiModule with Static with Twirl
