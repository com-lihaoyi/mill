package mill.playlib

import mill.T
import mill.twirllib._

trait Twirl extends TwirlModule with Layout {

  override def twirlSources = T.sources { app() }

  override def twirlVersion = T {
    playMinorVersion() match {
      case "2.6" => "1.3.16"
      case "2.7" => "1.4.2"
      case "2.8" => "1.5.1"
      case "2.9" => "1.6.2"
      case _ => "2.0.1"
    }
  }

  override def twirlImports = T {
    super.twirlImports() ++ Seq(
      "models._",
      "controllers._",
      "play.api.i18n._",
      "views.html._",
      "play.api.templates.PlayMagic._",
      "play.api.mvc._",
      "play.api.data._"
    )
  }

  def twirlOutput = T { Seq(compileTwirl().classes) }

  override def generatedSources = T {
    super.generatedSources() ++ twirlOutput()
  }
}
