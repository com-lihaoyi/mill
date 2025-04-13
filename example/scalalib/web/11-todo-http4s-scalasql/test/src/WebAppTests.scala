package webapp

import utest._
import cats.effect._
import cats.effect.testing.utest.EffectTestSuite
import org.http4s.client._

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
