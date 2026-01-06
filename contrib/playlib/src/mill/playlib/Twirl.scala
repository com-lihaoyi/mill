package mill.playlib

import mill.Task
import mill.twirllib.*

trait Twirl extends TwirlModule with Layout {

  override def twirlSources = Task { app() }

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
