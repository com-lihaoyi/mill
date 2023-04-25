package mill.main

import mill.api.{PathRef, Result}
import mill.{Agg, T}
import mill.define.{Cross, Module}
import mill.util.{TestEvaluator, TestUtil}
import utest.{TestSuite, Tests, assert, test}

object MainModuleTests extends TestSuite {

  object mainModule extends TestUtil.BaseModule with MainModule {
    def hello = T { Seq("hello", "world") }
    def hello2 = T { Map("1" -> "hello", "2" -> "world") }
  }

  object cleanModule extends TestUtil.BaseModule with MainModule {

    trait Cleanable extends Module {
      def target = T {
        os.write(T.dest / "dummy.txt", "dummy text")
        Seq(PathRef(T.dest))
      }
    }

    object foo extends Cleanable {
      object sub extends Cleanable
    }
    object bar extends Cleanable {
      override def target = T {
        os.write(T.dest / "dummy.txt", "dummy text")
        super.target() ++ Seq(PathRef(T.dest))
      }
    }
    object bazz extends Cross[Bazz]("1", "2", "3")
    trait Bazz extends Cleanable with Cross.Module[String]

    def all = T {
      foo.target()
      bar.target()
      bazz("1").target()
      bazz("2").target()
      bazz("3").target()
    }
  }

  override def tests: Tests = Tests {

    test("inspect") {
      val eval = new TestEvaluator(mainModule)
      test("single") {
        val res = eval.evaluator.evaluate(Agg(mainModule.inspect(eval.evaluator, "hello")))
        val Result.Success(value: String) = res.rawValues.head
        assert(
          res.failing.keyCount == 0,
          value.startsWith("hello("),
          value.contains("MainModuleTests.scala:")
        )
      }
      test("multi") {
        val res =
          eval.evaluator.evaluate(Agg(mainModule.inspect(eval.evaluator, "hello", "hello2")))
        val Result.Success(value: String) = res.rawValues.head
        assert(
          res.failing.keyCount == 0,
          value.startsWith("hello("),
          value.contains("MainModuleTests.scala:"),
          value.contains("\n\nhello2(")
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

    test("clean") {
      val ev = new TestEvaluator(cleanModule)
      val out = ev.evaluator.outPath

      def checkExists(exists: Boolean)(paths: os.SubPath*): Unit = {
        paths.foreach { path =>
          assert(os.exists(out / path) == exists)
        }
      }

      test("all") {
        val r1 = ev.evaluator.evaluate(Agg(cleanModule.all))
        assert(r1.failing.keyCount == 0)
        checkExists(true)(os.sub / "foo")

        val r2 = ev.evaluator.evaluate(Agg(cleanModule.clean(ev.evaluator)))
        assert(r2.failing.keyCount == 0)
        checkExists(false)(os.sub / "foo")
      }

      test("single-target") {
        val r1 = ev.evaluator.evaluate(Agg(cleanModule.all))
        assert(r1.failing.keyCount == 0)
        checkExists(true)(
          os.sub / "foo" / "target.json",
          os.sub / "foo" / "target.dest" / "dummy.txt",
          os.sub / "bar" / "target.json",
          os.sub / "bar" / "target.dest" / "dummy.txt"
        )

        println("\n\nCleaning foo.target\n\n")
        val r2 = ev.evaluator.evaluate(Agg(cleanModule.clean(ev.evaluator, "foo.target")))
        assert(r2.failing.keyCount == 0)
        checkExists(false)(
          os.sub / "foo" / "target.log",
          os.sub / "foo" / "target.json",
          os.sub / "foo" / "target.dest" / "dummy.txt"
        )
        checkExists(true)(
          os.sub / "bar" / "target.json",
          os.sub / "bar" / "target.dest" / "dummy.txt"
        )
      }

      test("single-module") {
        val r1 = ev.evaluator.evaluate(Agg(cleanModule.all))
        assert(r1.failing.keyCount == 0)
        checkExists(true)(
          os.sub / "foo" / "target.json",
          os.sub / "foo" / "target.dest" / "dummy.txt",
          os.sub / "bar" / "target.json",
          os.sub / "bar" / "target.dest" / "dummy.txt"
        )

        val r2 = ev.evaluator.evaluate(Agg(cleanModule.clean(ev.evaluator, "bar")))
        assert(r2.failing.keyCount == 0)
        checkExists(true)(
          os.sub / "foo" / "target.json",
          os.sub / "foo" / "target.dest" / "dummy.txt"
        )
        checkExists(false)(
          os.sub / "bar" / "target.json",
          os.sub / "bar" / "target.dest" / "dummy.txt"
        )
      }
    }
  }
}
