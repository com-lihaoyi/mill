package webapp

import utest._

object WebAppTests extends TestSuite {
  val port = sys.env.get("PORT").getOrElse("!").toInt

  def withServer[T](example: cask.main.Main)(f: String => T): T = {
    val server = io.undertow.Undertow.builder
      .addHttpListener(port, "localhost")
      .setHandler(example.defaultHandler)
      .build
    server.start()
    val res =
      try f(s"http://localhost:$port")
      finally server.stop()
    res
  }

  val tests = Tests {
    test("simpleRequest") - withServer(WebApp) { host =>
      val page = requests.get(host).text()
      assert(page.contains("What needs to be done?"))
    }
  }
}
