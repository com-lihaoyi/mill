import scala.scalajs.js

object Main {
  def main(args: Array[String]): Unit = {
    val value = js.Dynamic.global.process.env.MILL_TEST_CLIENT_ENV
    if (js.isUndefined(value)) throw new Exception("MILL_TEST_CLIENT_ENV is not set")
    println(s"MILL_TEST_CLIENT_ENV=$value")
  }
}
