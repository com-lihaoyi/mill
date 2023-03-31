package webapp
import scalatags.Text.all._
object WebApp extends cask.MainRoutes{
  @cask.get("/")
  def hello() = {
    println("webapp.WebApp.hello() called")
    doctype("html")(
      html(
        body(
          h1("Hello World"),
          p("I am cow")
        )
      )
    )
  }

  initialize()
}
