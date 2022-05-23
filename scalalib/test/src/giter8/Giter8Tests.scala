package mill.scalalib.giter8

import mill.T
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.main.MainModule
import mill.api.Loose.Agg

object Giter8Tests extends TestSuite {
  
  object mainModule extends TestUtil.BaseModule with MainModule

  override def tests: Tests = Tests {
    test("init") {
      val eval = new TestEvaluator(mainModule)
      test("hello world") {
        val outDir = os.temp.dir(prefix = "mill_giter8_test")
        val giter8Args = Seq(
          "sake92/mill-scala-hello.g8",
          s"-o=${outDir}",
          "--name=hello", // skip user interaction
          "--description=hello_desc" // need to pass all args
        )
        val evaluator = new TestEvaluator(mainModule)
        val res =  evaluator.evaluator.evaluate(Agg(mainModule.init(evaluator.evaluator, giter8Args: _*)))
        println(res)

        assert(os.exists(outDir / "build.sc"))
      }
    }
  }

}