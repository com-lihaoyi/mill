package webapp
import scalatags.Text.all._
object WebApp extends cask.MainRoutes{
  @cask.get("/")
  def hello() = {
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
