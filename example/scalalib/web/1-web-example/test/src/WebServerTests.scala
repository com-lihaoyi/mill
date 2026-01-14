package example
import utest.*

object WebServerTests extends TestSuite {
  def withServer[T](example: cask.main.Main)(block: => T): T = {
    val server = io.undertow.Undertow.builder
      .addHttpListener(8080, "localhost")
      .setHandler(example.defaultHandler)
      .build
    server.start()

    try block
    finally server.stop()
  }

  def tests = Tests {
    test("reverseString") - withServer(WebServer) {
      val response = requests.post(
        "http://localhost:8080/reverse-string",
        data = "helloworld"
      )
      assert(response.text() == "dlrowolleh")
    }
  }
}
