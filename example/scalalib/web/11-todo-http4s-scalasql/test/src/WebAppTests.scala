package webapp

import utest._
import cats.effect._
import cats.effect.testing.utest.EffectTestSuite
import org.http4s.client._
import utest._
import cats.effect._
import cats.effect.testing.utest.EffectTestSuite
import org.http4s._
import org.http4s.implicits._
import org.http4s.client._
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.headers._
import org.typelevel.ci.CIString

object WebAppTests extends EffectTestSuite[IO] {

  val mkClient = WebApp.mkService.map(Client.fromHttpApp[IO])

  val tests = Tests {
    test("simpleRequest") - {
      for {
        client <- mkClient
        page <- client.expect[String]("/")
      } yield assert(page.contains("What needs to be done?"))
    }

    test("POST /add/all adds a todo") {
      for {
        client <- mkClient
        req = Request[IO](method = Method.POST, uri = uri"/add/all")
          .withEntity("Learn Scala")
        res <- client.expect[String](req)
      } yield assert(res.contains("Learn Scala"))
    }

    test("POST /list/all returns the current list") {
      for {
        client <- mkClient
        _ <- client.status(Request[IO](
          method = Method.POST,
          uri = uri"/add/all"
        ).withEntity("Walk dog"))
        res <- client.expect[String](Request[IO](method = Method.POST, uri = uri"/list/all"))
      } yield assert(res.contains("Walk dog"))
    }

    test("POST /toggle/all toggles all todos") {
      for {
        client <- mkClient
        _ <- client.status(Request[IO](
          method = Method.POST,
          uri = uri"/add/all"
        ).withEntity("Clean room"))
        _ <- client.status(Request[IO](method = Method.POST, uri = uri"/toggle-all/all"))
        res <- client.expect[String](Request[IO](method = Method.POST, uri = uri"/list/all"))
      } yield assert(res.contains("checked"))
    }

    test("POST /delete/all/{id} deletes a todo") {
      for {
        client <- mkClient
        _ <- client.expect[String](
          Request[IO](method = Method.POST, uri = uri"/add/all").withEntity("Temp Task")
        )
        // Note: `Temp Task` is the fourth task created in this test suite, hence we delete task with index 4
        _ <- client.status(Request[IO](method = Method.POST, uri = uri"/delete/all/4"))
        res <- client.expect[String](Request[IO](method = Method.POST, uri = uri"/list/all"))
      } yield assert(!res.contains("Temp Task"))
    }

    test("POST /clear-completed/all removes completed todos") {
      for {
        client <- mkClient
        res <- client.expect[String](Request[IO](method = Method.POST, uri = uri"/list/all"))
        _ = assert(res.contains("Learn Scala"))
        _ <- client.status(Request[IO](method = Method.POST, uri = uri"/clear-completed/all"))
        res1 <- client.expect[String](Request[IO](method = Method.POST, uri = uri"/list/all"))
      } yield assert(!res1.contains("Learn Scala"))
    }
  }
}
