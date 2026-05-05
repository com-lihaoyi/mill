package example
object WebServer extends cask.MainRoutes {
  override def port = sys.env.getOrElse("PORT", "8080").toInt

  @cask.post("/reverse-string")
  def doThing(request: cask.Request) = {
    request.text().reverse
  }

  initialize()
}
