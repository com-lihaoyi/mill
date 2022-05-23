package mill.scalalib.giter8

import mill.T
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.api.Loose.Agg

object Giter8Tests extends TestSuite {
  
  object g8Module extends TestUtil.BaseModule with Giter8Module

  override def tests: Tests = Tests {
    test("init") {
      val eval = new TestEvaluator(g8Module)
      test("hello world") {
        val outDir = os.temp.dir(prefix = "mill_giter8_test")
        val giter8Args = Seq(
          "file://./scalalib/test/resources/giter8/hello.g8",
          s"-o=${outDir}",
          "--name=hello", // skip user interaction
          "--description=hello_desc" // need to pass all args
        )
        val evaluator = new TestEvaluator(g8Module)
        val res =  evaluator.evaluator.evaluate(Agg(g8Module.init(giter8Args: _*)))

        assert(os.exists(outDir / "build.sc"))
      }
    }
  }

}