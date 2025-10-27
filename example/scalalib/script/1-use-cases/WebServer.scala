//| mvnDeps: [com.lihaoyi::cask:0.9.1]

object WebServer extends cask.MainRoutes{
  @cask.post("/do-thing")
  def doThing(request: cask.Request) = {
    request.text().reverse
  }

  initialize()
}
