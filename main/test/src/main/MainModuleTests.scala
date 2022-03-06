package mill.main

import mill.api.Result
import mill.{Agg, T}
import mill.util.{TestEvaluator, TestUtil}
import utest.{TestSuite, Tests, test}

object MainModuleTests extends TestSuite {

  object mainModule extends TestUtil.BaseModule with MainModule {
    def hello = T { Seq("hello") }
    def hello2 = T { Seq("hello2") }
  }

  override def tests: Tests = Tests {
    test("show") {
      val evaluator = new TestEvaluator(mainModule)
      test("single") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.show(evaluator.evaluator, "hello")))

        assert(results.failing.keyCount == 0)

        val Result.Success(value) = results.rawValues.head

        assert(value == ujson.Obj.from(Map(
          "hello" -> ujson.Arr.from(Seq("hello"))
        )))
      }
      test("multi") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.show(
            evaluator.evaluator,
            "hello",
            "+",
            "hello2"
          )))

        assert(results.failing.keyCount == 0)

        val Result.Success(value) = results.rawValues.head

        assert(value == ujson.Obj.from(Map(
          "hello" -> ujson.Arr.from(Seq("hello")),
          "hello2" -> ujson.Arr.from(Seq("hello2"))
        )))
      }
    }
  }
}
