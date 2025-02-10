package mill.scalalib.giter8

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*
import mill.api.Loose.Agg
import mill.define.Discover

object Giter8Tests extends TestSuite {

  override def tests: Tests = Tests {
    test("init") {
      test("hello") {
        val template = getClass().getClassLoader().getResource("giter8/hello.g8").toExternalForm
        val templateString =
          if (template.startsWith("file:/") && template(6) != '/') {
            template.replace("file:", "file://")
          } else template

        object g8Module extends TestBaseModule with Giter8Module {
          lazy val millDiscover = Discover[this.type]
        }

        val evaluator = UnitTester(g8Module, null)

        val giter8Args = Seq(
          templateString,
          "--name=hello", // skip user interaction
          "--description=hello_desc" // need to pass all args
        )
        val res = evaluator.evaluator.evaluate(Agg(g8Module.init(giter8Args*)))

        val files = Seq(
          os.sub / "build.mill",
          os.sub / "README.md",
          os.sub / "hello/src/Hello.scala",
          os.sub / "hello/test/src/MyTest.scala"
        )

        assert(
          res.failing.size == 0,
          res.values.size == 1,
          files.forall(f => os.exists(g8Module.millSourcePath / "hello" / f))
        )
      }
    }
  }

}
