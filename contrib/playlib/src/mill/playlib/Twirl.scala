package mill.playlib

import mill.{Task, T}
import mill.twirllib._

trait Twirl extends TwirlModule with Layout {

  override def twirlSources = Task.sources { app() }

  override def twirlImports = Task {
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

  def twirlOutput = Task { Seq(compileTwirl().classes) }

  override def generatedSources = Task {
    super.generatedSources() ++ twirlOutput()
  }
}
