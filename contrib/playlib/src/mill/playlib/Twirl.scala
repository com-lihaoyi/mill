package mill
package playlib

import mill.twirllib._

trait Twirl extends TwirlModule with Layout {

  override def twirlSources=T.sources{ app() }

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

  def twirlOutput = T{Seq(compileTwirl().classes)}

  override def generatedSources = T{ super.generatedSources() ++ twirlOutput() }
}
