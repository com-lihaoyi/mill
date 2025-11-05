package mill.api

import mill.api.TaskCtx.ImplicitStub
import utest._

import scala.annotation.compileTimeOnly
import scala.language.implicitConversions

object ApplicativeTests extends TestSuite {

  class Counter {
    var value = 0
    def apply() = {
      value += 1
      value
    }
  }
  @compileTimeOnly("Task.ctx() can only be used within a Task{...} block")
  @ImplicitStub
  implicit def taskCtx: String = ???

  val tests = Tests {

    test("selfContained") {

      test("simple") - assert(TestOpt("lol " + 1) == TestOpt.some("lol 1"))
      test("singleSome") - assert(TestOpt("lol " + TestOpt.some("hello")()) == TestOpt.some("lol hello"))
      test("twoSomes") - assert(
        TestOpt(TestOpt.some("lol ")() + TestOpt.some("hello")()) == TestOpt.some("lol hello")
      )
      test("singleNone") - assert(TestOpt("lol " + TestOpt.none()) == TestOpt.none)
      test("twoNones") - assert(TestOpt("lol " + TestOpt.none() + TestOpt.none()) == TestOpt.none)
      test("moreThan22") {
        assert(
          TestOpt(
            "lol " +
              TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() +
              TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.some(" world")() +
              TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() +
              TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() +
              TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.none() + TestOpt.some(" moo")()
          ) == TestOpt.none
        )
        assert(
          TestOpt(
            "lol " +
              TestOpt.some("a")() + TestOpt.some("b")() + TestOpt.some("c")() + TestOpt.some("d")() +
              TestOpt.some("e")() + TestOpt.some("a")() + TestOpt.some("b")() + TestOpt.some("c")() +
              TestOpt.some("d")() + TestOpt.some("e")() + TestOpt.some("a")() + TestOpt.some("b")() +
              TestOpt.some("c")() + TestOpt.some("d")() + TestOpt.some("e")() + TestOpt.some("a")() +
              TestOpt.some("b")() + TestOpt.some("c")() + TestOpt.some("d")() + TestOpt.some("e")() +
              TestOpt.some("a")() + TestOpt.some("b")() + TestOpt.some("c")() + TestOpt.some("d")() +
              TestOpt.some("e")() + TestOpt.some("a")() + TestOpt.some("b")() + TestOpt.some("c")() +
              TestOpt.some("d")() + TestOpt.some("e")() + TestOpt.some("a")() + TestOpt.some("b")() +
              TestOpt.some("c")() + TestOpt.some("d")() + TestOpt.some("e")() + TestOpt.some("a")() +
              TestOpt.some("b")() + TestOpt.some("c")() + TestOpt.some("d")() + TestOpt.some("e")() +
              TestOpt.some("a")() + TestOpt.some("b")() + TestOpt.some("c")() + TestOpt.some("d")() +
              TestOpt.some("e")() + TestOpt.some("a")() + TestOpt.some("b")() + TestOpt.some("c")() +
              TestOpt.some("d")() + TestOpt.some("e")()
          ) == TestOpt.some("lol abcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcde")
        )
      }
    }
    test("context") {
      assert(TestOpt(TestOpt.ctx() + TestOpt.some("World")()) == TestOpt.some("hellooooWorld"))
    }
    test("capturing") {
      val lol = "lol "
      def hell(o: String) = "hell" + o
      test("simple") - assert(TestOpt(lol + 1) == TestOpt.some("lol 1"))
      test("singleSome") - assert(TestOpt(lol + TestOpt.some(hell("o"))()) == TestOpt.some("lol hello"))
      test("twoSomes") - assert(
        TestOpt(TestOpt.some(lol)() + TestOpt.some(hell("o"))()) == TestOpt.some("lol hello")
      )
      test("singleNone") - assert(TestOpt(lol + TestOpt.none()) == TestOpt.none)
      test("twoNones") - assert(TestOpt(lol + TestOpt.none() + TestOpt.none()) == TestOpt.none)
    }
    test("allowedLocalDef") {
      // Although x is defined inside the TestOpt{...} block, it is also defined
      // within the LHS of the Applyable#apply call, so it is safe to life it
      // out into the `zipMap` arguments list.
      val res = TestOpt { "lol " + new TestOpt(Some("hello").flatMap(x => Some(x))).apply() }
      assert(res == TestOpt.some("lol hello"))
    }
    test("upstreamAlwaysEvaluated") {
      // Whether or not control-flow reaches the Applyable#apply call inside an
      // TestOpt{...} block, we always evaluate the LHS of the Applyable#apply
      // because it gets lifted out of any control flow statements
      val counter = new Counter()
      def up = TestOpt { "lol " + counter() }
      val down = TestOpt { if ("lol".length > 10) up() else "fail" }
      assert(
        down == TestOpt.some("fail"),
        counter.value == 1
      )
    }
    test("upstreamEvaluatedOnlyOnce") {
      // Even if control-flow reaches the Applyable#apply call more than once,
      // it only gets evaluated once due to its lifting out of the TestOpt{...} block
      val counter = new Counter()
      def up = TestOpt { "lol " + counter() }
      def runTwice[T](t: => T) = (t, t)
      val down = TestOpt { runTwice(up()) }
      assert(
        down == TestOpt.some(("lol 1", "lol 1")),
        counter.value == 1
      )
    }
    test("evaluationsInsideLambdasWork") {
      // This required some fiddling with owner chains inside the macro to get
      // working, so ensure it doesn't regress
      val counter = new Counter()
      def up = TestOpt { "hello" + counter() }
      val down1 = TestOpt { (() => up())() }
      val down2 = TestOpt { Seq(1, 2, 3).map(n => up() * n) }
      assert(
        down1 == TestOpt.some("hello1"),
        down2 == TestOpt.some(Seq("hello2", "hello2hello2", "hello2hello2hello2"))
      )
    }
    test("appliesEvaluatedOncePerLexicalCallsite") {
      // If you have multiple Applyable#apply() lexically in the source code of
      // your TestOpt{...} call, each one gets evaluated once, even if the LHS of each
      // apply() call is identical. It's up to the downstream zipMap()
      // implementation to decide if it wants to dedup them or do other things.
      val counter = new Counter()
      def up = TestOpt { s"hello${counter()}" }
      val down = TestOpt { Seq(1, 2, 3).map(n => n + up() + up()) }
      assert(down == TestOpt.some(Seq("1hello1hello2", "2hello1hello2", "3hello1hello2")))
    }
    test("appliesEvaluateBeforehand") {
      // Every Applyable#apply() within a TestOpt{...} block evaluates before any
      // other logic within that block, even if they would happen first in the
      // normal Scala evaluation order
      val counter = new Counter()
      def up = TestOpt { counter() }
      val down = TestOpt {
        val res = counter()
        val one = up()
        val two = up()
        val three = up()
        (res, one, two, three)
      }
      assert(down == TestOpt.some((4, 1, 2, 3)))
    }
  }
}
