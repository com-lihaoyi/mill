package example
object WebServer extends cask.MainRoutes {
  @cask.post("/reverse-string")
  def doThing(request: cask.Request) = {
    request.text().reverse
  }

  initialize()
}
