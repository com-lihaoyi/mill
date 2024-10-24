package webapp
import scalatags.Text.all._
import scalatags.Text.tags2

object WebApp extends cask.MainRoutes {
  case class Todo(checked: Boolean, text: String)

  object Todo {
    implicit def todoRW: upickle.default.ReadWriter[Todo] = upickle.default.macroRW[Todo]
  }

  var todos = Seq(
    Todo(true, "Get started with Cask"),
    Todo(false, "Profit!")
  )

  @cask.post("/list/:state")
  def list(state: String) = renderBody(state)

  @cask.post("/add/:state")
  def add(state: String, request: cask.Request) = {
    todos = Seq(Todo(false, request.text())) ++ todos
    renderBody(state)
  }

  @cask.post("/delete/:state/:index")
  def delete(state: String, index: Int) = {
    todos = todos.patch(index, Nil, 1)
    renderBody(state)
  }

  @cask.post("/toggle/:state/:index")
  def toggle(state: String, index: Int) = {
    todos = todos.updated(index, todos(index).copy(checked = !todos(index).checked))
    renderBody(state)
  }

  @cask.post("/clear-completed/:state")
  def clearCompleted(state: String) = {
    todos = todos.filter(!_.checked)
    renderBody(state)
  }

  @cask.post("/toggle-all/:state")
  def toggleAll(state: String) = {
    val next = todos.filter(_.checked).size != 0
    todos = todos.map(_.copy(checked = next))
    renderBody(state)
  }

  def renderBody(state: String) /*: scalatags.Text.TypedTag[String] */ = {
    val filteredTodos = state match {
      case "all" => todos.zipWithIndex
      case "active" => todos.zipWithIndex.filter(!_._1.checked)
      case "completed" => todos.zipWithIndex.filter(_._1.checked)
    }
    div(
      header(
        cls := "header",
        h1("todos"),
        input(cls := "new-todo", placeholder := "What needs to be done?", autofocus := "")
      ),
      tags2.section(
        cls := "main",
        input(
          id := "toggle-all",
          cls := "toggle-all",
          `type` := "checkbox",
          if (todos.filter(_.checked).size != 0) checked else ()
        ),
        label(`for` := "toggle-all", "Mark all as complete"),
        ul(
          cls := "todo-list",
          for ((todo, index) <- filteredTodos) yield li(
            if (todo.checked) cls := "completed" else (),
            div(
              cls := "view",
              input(
                cls := "toggle",
                `type` := "checkbox",
                if (todo.checked) checked else (),
                data("todo-index") := index
              ),
              label(todo.text),
              button(cls := "destroy", data("todo-index") := index)
            ),
            input(cls := "edit", value := todo.text)
          )
        )
      ),
      footer(
        cls := "footer",
        span(cls := "todo-count", strong(todos.filter(!_.checked).size), " items left"),
        ul(
          cls := "filters",
          li(cls := "todo-all", a(if (state == "all") cls := "selected" else (), "All")),
          li(cls := "todo-active", a(if (state == "active") cls := "selected" else (), "Active")),
          li(
            cls := "todo-completed",
            a(if (state == "completed") cls := "selected" else (), "Completed")
          )
        ),
        button(cls := "clear-completed", "Clear completed")
      )
    )
  }

  @cask.get("/")
  def index() = {
    doctype("html")(
      html(
        lang := "en",
        head(
          meta(charset := "utf-8"),
          meta(name := "viewport", content := "width=device-width, initial-scale=1"),
          tags2.title("Template • TodoMVC"),
          link(rel := "stylesheet", href := "/static/index.css")
        ),
        body(
          tags2.section(cls := "todoapp", renderBody("all")),
          footer(
            cls := "info",
            p("Double-click to edit a todo"),
            p("Created by ", a(href := "http://todomvc.com", "Li Haoyi")),
            p("Part of ", a(href := "http://todomvc.com", "TodoMVC"))
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
