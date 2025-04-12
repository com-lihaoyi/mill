package webapp

import scalasql.H2Dialect._
import utest._
import webapp.WebApp.{DB, Todo}

object WebAppTests extends TestSuite {

  val h2Client = DB.getDatabaseClient
  DB.initializeSchema(h2Client)

  val tests = Tests {
    test("insert and list todos") {
      h2Client.transaction { db =>
        db.run(DB.Todos.insert.batched(_.checked, _.text)((false, "Learn Scala")))
      }

      val todos = h2Client.transaction { db =>
        db.run(DB.Todos.select).map(r => Todo(r.checked, r.text))
      }

      assert(todos.size == 1)
      assert(todos.head.text == "Learn Scala")
      assert(!todos.head.checked)
    }

    test("delete todo") {
      h2Client.transaction { db =>
        db.run(DB.Todos.insert.batched(_.checked, _.text)((false, "To Delete")))
      }

      val id = h2Client.transaction { db =>
        db.run(DB.Todos.select).find(_.text == "To Delete").get.id
      }

      h2Client.transaction { db =>
        db.run(DB.Todos.delete(_.id === id))
      }

      val todos = h2Client.transaction { db =>
        db.run(DB.Todos.select)
      }

      assert(todos.forall(_.text != "To Delete"))
    }

    test("toggle state") {
      h2Client.transaction { db =>
        db.run(DB.Todos.insert.batched(_.checked, _.text)((false, "To Toggle")))
      }

      val todo = h2Client.transaction { db =>
        db.run(DB.Todos.select).find(_.text == "To Toggle").get
      }

      val newState = !todo.checked

      h2Client.transaction { db =>
        db.updateRaw(s"UPDATE todos SET checked = ${newState} WHERE id = ${todo.id}")
      }

      val updated = h2Client.transaction { db =>
        db.run(DB.Todos.select).find(_.id == todo.id).get
      }

      assert(updated.checked == newState)
    }
  }
}
