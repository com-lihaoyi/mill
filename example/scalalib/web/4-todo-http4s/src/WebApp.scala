package webapp

import scala.concurrent.duration.Duration
import scalatags.Text.all.*
import scalatags.Text.tags2
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.scalatags.*
import org.http4s.server.staticcontent.*
import io.circe.*
import io.circe.generic.semiauto.*

object WebApp extends IOApp.Simple {
  case class Todo(checked: Boolean, text: String)

  def run = mkService.toResource.flatMap { service =>
    EmberServerBuilder
      .default[IO]
      .withHttpApp(service)
      .withPort(port"8084")
      .withShutdownTimeout(Duration.Zero)
      .build
  }.useForever

  def mkService =
    IO.ref(Seq(Todo(true, "Get started with http4s"), Todo(false, "Profit!"))).map { todosRef =>
      def apiRoutes = HttpRoutes.of[IO] {
        case POST -> Root / "list" / state =>
          Ok(renderBody(state))

        case request @ POST -> Root / "add" / state =>
          for {
            text <- request.as[String]
            _ <- todosRef.update(Seq(Todo(false, text)) ++ _)
            response <- Ok(renderBody(state))
          } yield response

        case POST -> Root / "delete" / state / IntVar(index) =>
          todosRef.update(_.patch(index, Nil, 1)) *> Ok(renderBody(state))

        case POST -> Root / "toggle" / state / IntVar(index) =>
          todosRef.update { todos =>
            todos.updated(index, todos(index).copy(checked = !todos(index).checked))
          } *> Ok(renderBody(state))

        case POST -> Root / "clear-completed" / state =>
          todosRef.update(_.filter(!_.checked)) *> Ok(renderBody(state))

        case POST -> Root / "toggle-all" / state =>
          todosRef.update { todos =>
            val next = todos.filter(_.checked).size != 0
            todos.map(_.copy(checked = next))
          } *> Ok(renderBody(state))

        case GET -> Root => Ok(index)
      }

      def renderBody(state: String) =
        todosRef.get.map { todos =>
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
                li(
                  cls := "todo-active",
                  a(if (state == "active") cls := "selected" else (), "Active")
                ),
                li(
                  cls := "todo-completed",
                  a(if (state == "completed") cls := "selected" else (), "Completed")
                )
              ),
              button(cls := "clear-completed", "Clear completed")
            )
          )
        }

      def index = renderBody("all").map { renderedBody =>
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
              tags2.section(cls := "todoapp", renderedBody),
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

      def staticRoutes = resourceServiceBuilder[IO]("webapp").withPathPrefix("static").toRoutes

      (apiRoutes <+> staticRoutes).orNotFound
    }

}
