package mill.integration
import utest.*
object InitMavenTheAlgorithmsJavaTests extends InitTestSuite(
      "https://github.com/TheAlgorithms/Java",
      "8e30bcbb02f14f118cc63f24b3c44b7ff7f66ba8",
      Seq("--mill-jvm-id", "21"),
      gitBranch = false
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("compile").isSuccess
    )
    test("test") - assert(
      eval(("test.testOnly", "com.thealgorithms.strings.ZAlgorithmTest")).isSuccess
    )
    test("pmd") - assert(
      eval(("resolve", "pmd")).isSuccess
    )
    test("jacoco") - assert(
      eval(("resolve", "test.jacocoEnabled")).isSuccess
    )
    test("issues") {
      test("Expected java.lang.AssertionError") - assert(
        !eval(("test.testOnly", "com.thealgorithms.conversions.AffineConverterTest")).isSuccess
      )
      test("javadoc errors") - assert(
        !eval("javadocGenerated").isSuccess
      )
      test("suppression of pmd violations not supported") - assert(
        !eval("pmd").isSuccess
      )
    }
  }
}
