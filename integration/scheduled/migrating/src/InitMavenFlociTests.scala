package mill.integration
import utest.*
object InitMavenFlociTests extends InitTestSuite(
      "https://github.com/floci-io/floci",
      "2.3.0",
      Seq("--mill-jvm-id", "25")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("compile").isSuccess
    )
    test("quarkusRunJar") - assert(
      eval("quarkusRunJar").isSuccess
    )
    test("testCompile") - assert(
      eval("test.compile").isSuccess
    )
    test("test") - assert(
      eval((
        "test.testOnly",
        "io.github.hectorvent.floci.config.ApplicationDefaultsTest",
        "io.github.hectorvent.floci.config.RedshiftProxyConfigTest"
      )).isSuccess
    )
  }
}
