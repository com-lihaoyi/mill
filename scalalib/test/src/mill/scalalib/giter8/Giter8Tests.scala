package mill.scalalib.giter8

import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.api.Loose.Agg
import os.Path

object Giter8Tests extends TestSuite {

  override def tests: Tests = Tests {
    test("init") {
      test("hello") {
        val rootDir = os.temp.dir(prefix = "mill_giter8_test")
        val template = getClass().getClassLoader().getResource("giter8/hello.g8").toExternalForm
        val templateString =
          if (template.startsWith("file:/") && template(6) != '/') {
            template.replace("file:", "file://")
          } else template

        object g8Module extends TestUtil.BaseModule with Giter8Module {
          override def millSourcePath: Path = rootDir
        }

        val evaluator = new TestEvaluator(g8Module)

        val giter8Args = Seq(
          templateString,
          "--name=hello", // skip user interaction
          "--description=hello_desc" // need to pass all args
        )
        val res = evaluator.evaluator.evaluate(Agg(g8Module.init(giter8Args: _*)))

        val files = Seq(
          os.sub / "build.sc",
          os.sub / "README.md",
          os.sub / "hello" / "src" / "Hello.scala",
          os.sub / "hello" / "test" / "src" / "MyTest.scala"
        )

        assert(
          res.failing.keyCount == 0,
          res.values.size == 1,
          files.forall(f => os.exists(rootDir / "hello" / f))
        )
      }
    }
  }

}
