package mill.playlib

import mill.{task, T}
import mill.twirllib._

trait Twirl extends TwirlModule with Layout {

  override def twirlSources = task.sources { app() }

  override def twirlImports = task {
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

  def twirlOutput = task { Seq(compileTwirl().classes) }

  override def generatedSources = task {
    super.generatedSources() ++ twirlOutput()
  }
}
