package mill.main

import mill.api.Result
import mill.{Agg, T}
import mill.util.{TestEvaluator, TestUtil}
import utest.{assert, TestSuite, Tests, test}

object MainModuleTests extends TestSuite {

  object mainModule extends TestUtil.BaseModule with MainModule {
    def hello = T { Seq("hello", "world") }
    def hello2 = T { Map("1" -> "hello", "2" -> "world") }
  }

  override def tests: Tests = Tests {
    test("inspect") {
      val eval = new TestEvaluator(mainModule)
      test("single") {
        val res = eval.evaluator.evaluate(Agg(mainModule.inspect(eval.evaluator, "hello")))
        val Result.Success(value: String) = res.rawValues.head
        assert(
          res.failing.keyCount == 0,
          value.startsWith("hello(MainModuleTests.scala:")
        )
      }
      test("multi") {
        val res = eval.evaluator.evaluate(Agg(mainModule.inspect(eval.evaluator, "hello", "hello2")))
        val Result.Success(value: String) = res.rawValues.head
        assert(
          res.failing.keyCount == 0,
          value.startsWith("hello(MainModuleTests.scala:"),
          value.contains("\n\nhello2(MainModuleTests.scala:")
        )
      }
    }
    test("show") {
      val evaluator = new TestEvaluator(mainModule)
      test("single") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.show(evaluator.evaluator, "hello")))

        assert(results.failing.keyCount == 0)

        val Result.Success(value) = results.rawValues.head

        assert(value == ujson.Arr.from(Seq("hello", "world")))
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

        assert(value == ujson.Arr.from(Seq(
          ujson.Arr.from(Seq("hello", "world")),
          ujson.Obj.from(Map("1" -> "hello", "2" -> "world"))
        )))
      }
    }
    test("showNamed") {
      val evaluator = new TestEvaluator(mainModule)
      test("single") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.showNamed(evaluator.evaluator, "hello")))

        assert(results.failing.keyCount == 0)

        val Result.Success(value) = results.rawValues.head

        assert(value == ujson.Obj.from(Map(
          "hello" -> ujson.Arr.from(Seq("hello", "world"))
        )))
      }
      test("multi") {
        val results =
          evaluator.evaluator.evaluate(Agg(mainModule.showNamed(
            evaluator.evaluator,
            "hello",
            "+",
            "hello2"
          )))

        assert(results.failing.keyCount == 0)

        val Result.Success(value) = results.rawValues.head

        assert(value == ujson.Obj.from(Map(
          "hello" -> ujson.Arr.from(Seq("hello", "world")),
          "hello2" -> ujson.Obj.from(Map("1" -> "hello", "2" -> "world"))
        )))
      }
    }
  }
}
