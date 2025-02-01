package webapp
import scalatags.Text.all._
import scalatags.Text.tags2
import shared.{Shared, Todo}

object WebApp extends cask.MainRoutes {
  override def port = 8083
  var todos = Seq(
    Todo(true, "Get started with Cask"),
    Todo(false, "Profit!")
  )

  @cask.post("/list/:state")
  def list(state: String) = upickle.default.write(todos)

  @cask.post("/add/:state")
  def add(state: String, request: cask.Request) = {
    todos = Seq(Todo(false, request.text())) ++ todos
    upickle.default.write(todos)
  }

  @cask.post("/delete/:state/:index")
  def delete(state: String, index: Int) = {
    todos = todos.patch(index, Nil, 1)
    upickle.default.write(todos)
  }

  @cask.post("/toggle/:state/:index")
  def toggle(state: String, index: Int) = {
    todos = todos.updated(index, todos(index).copy(checked = !todos(index).checked))
    upickle.default.write(todos)
  }

  @cask.post("/clear-completed/:state")
  def clearCompleted(state: String) = {
    todos = todos.filter(!_.checked)
    upickle.default.write(todos)
  }

  @cask.post("/toggle-all/:state")
  def toggleAll(state: String) = {
    val next = todos.filter(_.checked).size != 0
    todos = todos.map(_.copy(checked = next))
    upickle.default.write(todos)
  }

  @cask.get("/")
  def index() = {
    doctype("html")(
      html(lang := "en")(
        head(
          meta(charset := "utf-8"),
          meta(name := "viewport", content := "width=device-width, initial-scale=1"),
          tags2.title("Template • TodoMVC"),
          link(rel := "stylesheet", href := "/static/index.css")
        ),
        body(
          tags2.section(cls := "todoapp")(Shared.renderBody(todos, "all")),
          footer(cls := "info")(
            p("Double-click to edit a todo"),
            p("Created by ")(
              a(href := "http://todomvc.com")("Li Haoyi")
            ),
            p("Part of ")(
              a(href := "http://todomvc.com")("TodoMVC")
            )
          ),
          script(src := "/static/main.js")
        )
      )
    )
  }

  @cask.staticResources("/static")
  def static() = "webapp"

  initialize()
}
