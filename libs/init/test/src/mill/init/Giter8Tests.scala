package mill.init

import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.api.Discover

object Giter8Tests extends TestSuite {

  override def tests: Tests = Tests {
    test("init") {
      test("hello") {
        val template = getClass().getClassLoader().getResource("giter8/hello.g8").toExternalForm
        val templateString =
          if (template.startsWith("file:/") && template(6) != '/') {
            template.replace("file:", "file://")
          } else template

        object g8Module extends TestRootModule with Giter8Module {
          lazy val millDiscover = Discover[this.type]
        }

        UnitTester(g8Module, null).scoped { evaluator =>

          val giter8Args = Seq(
            templateString,
            "--name=hello", // skip user interaction
            "--description=hello_desc" // need to pass all args
          )
          val res = evaluator.evaluator.execute(Seq(g8Module.init(giter8Args*))).executionResults

          val files = Seq(
            os.sub / "build.mill",
            os.sub / "README.md",
            os.sub / "hello/src/Hello.scala",
            os.sub / "hello/test/src/MyTest.scala"
          )

          assertAll(
            res.transitiveFailing.size == 0,
            res.values.size == 1,
            files.forall(f => os.exists(g8Module.moduleDir / "hello" / f))
          )
        }
      }
    }
  }

}
