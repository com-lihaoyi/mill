package webapp

import utest.*
import cats.effect.*
import cats.effect.testing.utest.EffectTestSuite
import org.http4s.client.*

object WebAppTests extends EffectTestSuite[IO] {

  val mkClient = WebApp.mkService.map(Client.fromHttpApp[IO])

  val tests = Tests {
    test("simpleRequest") - {
      for {
        client <- mkClient
        page <- client.expect[String]("/")
      } yield assert(page.contains("What needs to be done?"))
    }
  }
}
