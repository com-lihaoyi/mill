package mill.integration
import utest.*
object SbtScala3Tests extends InitBuildTestSuite {
  def gitUrl = "https://github.com/scala/scala3"
  def gitRev = "3.7.4"
  def initArgs = Seq("--mill-jvm-id", "17")
  def tests = Tests {
    test("shared base directory") {
      val result = tester.eval(("resolve", "compiler._"))
      assert(
        result.out ==
          """compiler.scala3-compiler
            |compiler.scala3-compiler-bootstrapped
            |compiler.scala3-compiler-bootstrapped-new
            |compiler.scala3-compiler-nonbootstrapped""".stripMargin
      )
    }
  }
}
