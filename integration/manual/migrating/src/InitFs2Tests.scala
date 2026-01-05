package mill.integration
import utest.*
object InitFs2Tests extends InitTestSuite {
  def gitUrl = "https://github.com/typelevel/fs2"
  def gitRev = "v3.12.2"
  def initArgs = Seq("--mill-jvm-id", "17")
  def tests = Tests {
    test("compile") - assert(
      eval("{core,integration,io,protocols,reactive-streams,scodec}.__.compile").isSuccess
    )
    test("test") - assert(
      eval("{core,protocols,scodec}.{js,jvm}.__.test").isSuccess,
      eval("{integration,reactive-streams}._.test").isSuccess
    )
    test("scalafmt") - assert(
      eval("__.checkFormat").isSuccess
    )
    test("features") {
      test("auto-plugins are mapped selectively") - assert(
        !eval("mdoc._.reformat").isSuccess
      )
    }
    test("issues") {
      test("Java 9+ API not available with scalacOptions --release 8") - assert(
        !eval("benchmark.2_13_16.compile").isSuccess
      )
      test("path errors for ScalaJS resource files") - assert(
        !eval(("io.js.2_13_16.test.testOnly", "fs2.io.net.tls.TLSSocketSuite")).isSuccess
      )
      test("unsupported ScalaNative version") - assert(
        !eval("scodec.native.2_13_16.test.scalaNativeWorkerClasspath").isSuccess
      )
    }
  }
}
