package mill.playlib

import mill.define.Task
import mill.playlib.api.Versions
import mill.scalalib._
import mill.{Agg, Args, T}

trait PlayApiModule extends Dependencies with Router with Server {
  trait PlayTests extends ScalaTests with TestModule.ScalaTest {
    override def ivyDeps = T {
      val scalatestPlusPlayVersion = playMinorVersion() match {
        case Versions.PLAY_2_6 => "3.1.3"
        case Versions.PLAY_2_7 => "4.0.3"
        case Versions.PLAY_2_8 => "5.1.0"
        case Versions.PLAY_2_9 => "6.0.0"
        case _ => "7.0.0"
      }
      Agg(ivy"org.scalatestplus.play::scalatestplus-play::${scalatestPlusPlayVersion}")
    }
    override def sources = T.sources { millSourcePath }
  }

  def start(args: Task[Args] = T.task(Args())) = T.command { run(args) }

}
trait PlayModule extends PlayApiModule with Static with Twirl {
  override def twirlVersion = T {
    playMinorVersion() match {
      case "2.6" => "1.3.16"
      case "2.7" => "1.4.2"
      case "2.8" => "1.5.1"
      case "2.9" => "1.6.2"
      case _ => "2.0.1"
    }
  }
}
