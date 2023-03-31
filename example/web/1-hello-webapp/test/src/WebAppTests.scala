package webapp
import io.undertow.Undertow

import utest._

object WebAppTests extends TestSuite{
  def withServer[T](example: cask.main.Main)(f: String => T): T = {
    val server = Undertow.builder
      .addHttpListener(8081, "localhost")
      .setHandler(example.defaultHandler)
      .build
    server.start()
    val res =
      try f("http://localhost:8081")
      finally server.stop()
    res
  }

  val tests = Tests {
    test("simpleRequest") - withServer(WebApp) { host =>
      val body = requests.get(host).text()

      assert(
        body.contains("<h1>Hello World</h1>"),
        body.contains("<p>I am cow</p>"),
      )
    }
  }
}
