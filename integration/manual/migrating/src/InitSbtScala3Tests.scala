package mill.integration
import utest.*
object InitSbtScala3Tests extends InitTestSuite(
      "https://github.com/scala/scala3",
      "3.7.4",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("shared base directory") {
      val pkgLines = os.read.lines(tester.workspacePath / "compiler/package.mill")
      assert(pkgLines.count(_.trim == "def moduleDir = outer.moduleDir") == 4)
    }
    test("mimaBackwardIssueFilters") - assert(
      eval(("show", "library.scala3-library-bootstrapped.mimaBackwardIssueFilters")).isSuccess
    )
    test("mimaForwardIssueFilters") - assert(
      eval(("show", "tasty.tasty-core-bootstrapped.mimaForwardIssueFilters")).isSuccess
    )
  }
}
