package mill.playlib

import mill.define.Task
import mill.playlib.api.Versions
import mill.scalalib._
import mill.{Args, T}
import mill.api.PathRef
import mill.define.Target

trait PlayApiModule extends Dependencies with Router with Server {
  trait PlayTests extends ScalaTests with TestModule.ScalaTest {
    override def ivyDeps = Task {
      val scalatestPlusPlayVersion = playMinorVersion() match {
        case Versions.PLAY_2_6 => "3.1.3"
        case Versions.PLAY_2_7 => "4.0.3"
        case Versions.PLAY_2_8 => "5.1.0"
        case Versions.PLAY_2_9 => "6.0.0"
        case _ => "7.0.0"
      }
      Seq(ivy"org.scalatestplus.play::scalatestplus-play::${scalatestPlusPlayVersion}")
    }
    override def sources: Target[Seq[PathRef]] = Task.Sources { moduleDir }
  }

  def start(args: Task[Args] = Task.Anon(Args())) = Task.Command { run(args)() }

}
trait PlayModule extends PlayApiModule with Static with Twirl {
  override def twirlScalaVersion: T[String] = Task {
    if scalaVersion().startsWith("2.13.") then
      // TODO: This determines which version of `twirl-compiler` library
      // will be used to source-generate scala files from twirl sources,
      // which will then be further compiled by the Scala compiler corresponding to `scalaVersion`.
      // The Scala 3 version of `twirl-compiler` generates code that
      // is not source compatible with scala 2 - so we should downgrade it to 2.13 version.
      mill.util.BuildInfo.workerScalaVersion213
    else
      super.twirlScalaVersion()
  }

  override def twirlVersion: T[String] = Task {
    playMinorVersion() match {
      case "2.6" => "1.3.16"
      case "2.7" => "1.4.2"
      case "2.8" => "1.5.1"
      case "2.9" => "1.6.2"
      case _ => "2.0.1"
    }
  }
}
