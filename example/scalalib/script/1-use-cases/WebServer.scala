//| mvnDeps: [com.lihaoyi::cask:0.9.1]
package app
object MinimalApplication extends cask.MainRoutes{
  @cask.post("/do-thing")
  def doThing(request: cask.Request) = {
    request.text().reverse
  }

  initialize()
}
