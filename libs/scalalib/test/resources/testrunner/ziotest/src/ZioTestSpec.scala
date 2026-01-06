package mill.scalalib

import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.io.IOException

import HelloWorld.*

object HelloWorld {
  def sayHello: ZIO[Any, IOException, Unit] =
    Console.printLine("Hello, World!")
}

object ZioTestSpec extends ZIOSpecDefault {
  def spec = suite("ZioTestSpec")(
    test("sayHello correctly displays output") {
      for {
        _ <- sayHello
        output <- TestConsole.output
      } yield assertTrue(output == Vector("Hello, World!\n"))
    }
  )
}
