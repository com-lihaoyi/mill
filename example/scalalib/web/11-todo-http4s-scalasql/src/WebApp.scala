package webapp

import scala.concurrent.duration.Duration
import scalatags.Text.all._
import scalatags.Text.tags2
import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.scalatags._
import org.http4s.server.staticcontent._
import io.circe._
import io.circe.generic.semiauto._
import scalasql.H2Dialect._
import scalasql.core.DbClient
import scalasql.{Sc, Table}
import webapp.WebApp.DB.Todos

object WebApp extends IOApp.Simple {
  case class Todo(checked: Boolean, text: String)

  object DB {

    //  Table Definition and its corresponding ORM mapping
    case class Todos[T[_]](
        id: T[Int],
        checked: T[Boolean],
        text: T[String]
    )

    object Todos extends Table[Todos]

    def getDatabaseClient: DbClient.DataSource = {
      // The example H2 database comes from the library `com.h2database:h2:2.2.224`
      val dataSource = new org.h2.jdbcx.JdbcDataSource
      dataSource.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")

      new scalasql.DbClient.DataSource(
        dataSource,
        config = new scalasql.Config {}
      )
    }

    def initializeSchema(h2Client: scalasql.DbClient.DataSource): Unit = {
      h2Client.transaction { db =>
        db.updateRaw(
          """
          CREATE TABLE IF NOT EXISTS todos (
            id INT AUTO_INCREMENT PRIMARY KEY,
            checked BOOLEAN NOT NULL,
            text VARCHAR NOT NULL
        );
        """
        )
        println("Schema initialized (if not exists).") // Optional logging
      }
    }

  }

  lazy val dbClient: DbClient.DataSource = DB.getDatabaseClient
  DB.initializeSchema(dbClient)

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
        case POST -> Root / "list" / state => {
          for {
            todos <- fetchTodos
            response <- Ok(renderBody(state, todos))
          } yield response
        }

        case request @ POST -> Root / "add" / state => {
          for {
            text <- request.as[String]
            _ <- IO.delay {
              dbClient.transaction { db =>
                db.run(Todos.insert.batched(_.checked, _.text)(
                  (false, text)
                ))
              }
            }
            todos <- fetchTodos
            response <- Ok(renderBody(state, todos))
          } yield response
        }

        case POST -> Root / "delete" / state / IntVar(index) => {
          for {
            _ <- IO.delay {
              dbClient.transaction { db =>
                db.run(Todos.delete(_.id === index))
              }
            }
            todos <- fetchTodos
            response <- Ok(renderBody(state, todos))
          } yield response
        }

        case POST -> Root / "toggle" / state / IntVar(index) => {
          for {
            _ <- IO.delay {
              dbClient.transaction { db =>
                db.run(Todos.select.filter(_.id === index)).map(_.checked).head
              }
            }
            todos <- fetchTodos
            response <- Ok(renderBody(state, todos))
          } yield response
        }

        case POST -> Root / "clear-completed" / state => {
          for {
            _ <- IO.delay {
              dbClient.transaction { db =>
                db.run(Todos.delete(_.checked === true))
              }
            }
            todos <- fetchTodos
            response <- Ok(renderBody(state, todos))
          } yield response
        }

        case POST -> Root / "toggle-all" / state => {
          for {
            _ <- IO.delay {
              dbClient.transaction { db =>
                db.updateRaw(
                  """
                    UPDATE todos SET checked = CASE
                        WHEN (SELECT COUNT(*) FROM todos WHERE checked = TRUE) > 0 THEN FALSE
                        ELSE TRUE
                    END;
                    |
                    |""".stripMargin
                )
              }
            }
            todos <- fetchTodos
            response <- Ok(renderBody(state, todos))
          } yield response
        }

        case GET -> Root => Ok(index)
      }

      def fetchTodos = {
        IO.delay {
          dbClient.transaction { db =>
            db.run(Todos.select).sortBy(_.id)
          }.map(r => Todo(r.checked, r.text))
        }
      }

      def renderBody(state: String, todos: Seq[Todo]) =
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

      def index = renderBody("all", Seq()).map { renderedBody =>
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
