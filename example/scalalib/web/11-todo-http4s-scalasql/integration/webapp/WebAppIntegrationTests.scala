package webapp

import utest._
import cats.effect._
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.testcontainers.utility.DockerImageName
import scalasql.core.DbClient
import scalasql.{Sc, Table}
import WebApp.Todo

object WebAppIntegrationTests extends TestSuite with ForAllTestContainer {

  override val container = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16.2"),
    databaseName = "testdb",
    username = "testuser",
    password = "testpass"
  )

  lazy val dbClient: DbClient.DataSource = {
    val dataSource = new org.postgresql.ds.PGSimpleDataSource()
    dataSource.setUrl(container.jdbcUrl)
    dataSource.setUser(container.username)
    dataSource.setPassword(container.password)

    new scalasql.DbClient.DataSource(
      dataSource,
      config = new scalasql.Config {}
    )
  }

  override def afterStart(): Unit = {
    // Initialize schema for Postgres
    dbClient.transaction { db =>
      db.updateRaw(
        """
        CREATE TABLE IF NOT EXISTS todos (
          id SERIAL PRIMARY KEY,
          checked BOOLEAN NOT NULL,
          text VARCHAR NOT NULL
        );
        """
      )
    }
  }

  val tests = Tests {
    test("Insert and fetch todos from Postgres database") {
      dbClient.transaction { db =>
        // Insert a todo
        db.run(WebApp.DB.Todos.insert.batched(_.checked, _.text)(
          (false, "Test Integration")
        ))

        // Fetch todos
        val todos = db.run(WebApp.DB.Todos.select)

        assert(todos.length == 1)
        assert(todos.head.text == "Test Integration")
        assert(!todos.head.checked)
      }
    }

    test("Toggle a todo") {
      dbClient.transaction { db =>
        val id = db.run(WebApp.DB.Todos.insert.batched(_.checked, _.text)(
          (false, "Toggle Test")
        ))(0)

        // Update toggle
        db.updateRaw(s"UPDATE todos SET checked = NOT checked WHERE id = $id")

        val updatedTodo = db.run(WebApp.DB.Todos.select.filter(_.id === id)).head

        assert(updatedTodo.checked == true)
      }
    }

    test("Delete a todo") {
      dbClient.transaction { db =>
        val id = db.run(WebApp.DB.Todos.insert.batched(_.checked, _.text)(
          (false, "Delete Me")
        ))(0)

        db.run(WebApp.DB.Todos.delete(_.id === id))

        val todos = db.run(WebApp.DB.Todos.select.filter(_.id === id))

        assert(todos.isEmpty)
      }
    }
  }
}
