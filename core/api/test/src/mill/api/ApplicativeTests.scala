package mill.api

import mill.api.TaskCtx.ImplicitStub
import utest.*

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

      test("simple") - assert(TestApply("lol " + 1) == TestApply.some("lol 1"))
      test("singleSome") - assert(
        TestApply("lol " + TestApply.some("hello")()) == TestApply.some("lol hello")
      )
      test("twoSomes") - assert(
        TestApply(
          TestApply.some("lol ")() + TestApply.some("hello")()
        ) == TestApply.some("lol hello")
      )
      test("singleNone") - assert(TestApply("lol " + TestApply.none()) == TestApply.none)
      test("twoNones") - assert(
        TestApply("lol " + TestApply.none() + TestApply.none()) == TestApply.none
      )
      test("moreThan22") {
        assert(
          TestApply(
            "lol " +
              TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() +
              TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() + TestApply.some(
                " world"
              )() +
              TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() +
              TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() +
              TestApply.none() + TestApply.none() + TestApply.none() + TestApply.none() + TestApply.some(
                " moo"
              )()
          ) == TestApply.none
        )
        assert(
          TestApply(
            "lol " +
              TestApply.some("a")() + TestApply.some("b")() + TestApply.some(
                "c"
              )() + TestApply.some("d")() +
              TestApply.some("e")() + TestApply.some("a")() + TestApply.some(
                "b"
              )() + TestApply.some("c")() +
              TestApply.some("d")() + TestApply.some("e")() + TestApply.some(
                "a"
              )() + TestApply.some("b")() +
              TestApply.some("c")() + TestApply.some("d")() + TestApply.some(
                "e"
              )() + TestApply.some("a")() +
              TestApply.some("b")() + TestApply.some("c")() + TestApply.some(
                "d"
              )() + TestApply.some("e")() +
              TestApply.some("a")() + TestApply.some("b")() + TestApply.some(
                "c"
              )() + TestApply.some("d")() +
              TestApply.some("e")() + TestApply.some("a")() + TestApply.some(
                "b"
              )() + TestApply.some("c")() +
              TestApply.some("d")() + TestApply.some("e")() + TestApply.some(
                "a"
              )() + TestApply.some("b")() +
              TestApply.some("c")() + TestApply.some("d")() + TestApply.some(
                "e"
              )() + TestApply.some("a")() +
              TestApply.some("b")() + TestApply.some("c")() + TestApply.some(
                "d"
              )() + TestApply.some("e")() +
              TestApply.some("a")() + TestApply.some("b")() + TestApply.some(
                "c"
              )() + TestApply.some("d")() +
              TestApply.some("e")() + TestApply.some("a")() + TestApply.some(
                "b"
              )() + TestApply.some("c")() +
              TestApply.some("d")() + TestApply.some("e")()
          ) == TestApply.some("lol abcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcde")
        )
      }
    }
    test("context") {
      assert(
        TestApply(TestApply.ctx() + TestApply.some("World")()) == TestApply.some("hellooooWorld")
      )
    }
    test("capturing") {
      val lol = "lol "
      def hell(o: String) = "hell" + o
      test("simple") - assert(TestApply(lol + 1) == TestApply.some("lol 1"))
      test("singleSome") - assert(
        TestApply(lol + TestApply.some(hell("o"))()) == TestApply.some("lol hello")
      )
      test("twoSomes") - assert(
        TestApply(
          TestApply.some(lol)() + TestApply.some(hell("o"))()
        ) == TestApply.some("lol hello")
      )
      test("singleNone") - assert(TestApply(lol + TestApply.none()) == TestApply.none)
      test("twoNones") - assert(
        TestApply(lol + TestApply.none() + TestApply.none()) == TestApply.none
      )
    }
    test("allowedLocalDef") {
      // Although x is defined inside the Opt{...} block, it is also defined
      // within the LHS of the Applyable#apply call, so it is safe to life it
      // out into the `zipMap` arguments list.
      val res = TestApply { "lol " + TestApply(Some("hello").flatMap(x => Some(x))).apply() }
      assert(res == TestApply.some("lol hello"))
    }
    test("upstreamAlwaysEvaluated") {
      // Whether or not control-flow reaches the Applyable#apply call inside an
      // Opt{...} block, we always evaluate the LHS of the Applyable#apply
      // because it gets lifted out of any control flow statements
      val counter = Counter()
      def up = TestApply { "lol " + counter() }
      val down = TestApply { if ("lol".length > 10) up() else "fail" }
      assert(
        down == TestApply.some("fail"),
        counter.value == 1
      )
    }
    test("upstreamEvaluatedOnlyOnce") {
      // Even if control-flow reaches the Applyable#apply call more than once,
      // it only gets evaluated once due to its lifting out of the Opt{...} block
      val counter = Counter()
      def up = TestApply { "lol " + counter() }
      def runTwice[T](t: => T) = (t, t)
      val down = TestApply { runTwice(up()) }
      assert(
        down == TestApply.some(("lol 1", "lol 1")),
        counter.value == 1
      )
    }
    test("evaluationsInsideLambdasWork") {
      // This required some fiddling with owner chains inside the macro to get
      // working, so ensure it doesn't regress
      val counter = Counter()
      def up = TestApply { "hello" + counter() }
      val down1 = TestApply { (() => up())() }
      val down2 = TestApply { Seq(1, 2, 3).map(n => up() * n) }
      assert(
        down1 == TestApply.some("hello1"),
        down2 == TestApply.some(Seq("hello2", "hello2hello2", "hello2hello2hello2"))
      )
    }
    test("appliesEvaluatedOncePerLexicalCallsite") {
      // If you have multiple Applyable#apply() lexically in the source code of
      // your Opt{...} call, each one gets evaluated once, even if the LHS of each
      // apply() call is identical. It's up to the downstream zipMap()
      // implementation to decide if it wants to dedup them or do other things.
      val counter = Counter()
      def up = TestApply { s"hello${counter()}" }
      val down = TestApply { Seq(1, 2, 3).map(n => n + up() + up()) }
      assert(down == TestApply.some(Seq("1hello1hello2", "2hello1hello2", "3hello1hello2")))
    }
    test("appliesEvaluateBeforehand") {
      // Every Applyable#apply() within a Opt{...} block evaluates before any
      // other logic within that block, even if they would happen first in the
      // normal Scala evaluation order
      val counter = Counter()
      def up = TestApply { counter() }
      val down = TestApply {
        val res = counter()
        val one = up()
        val two = up()
        val three = up()
        (res, one, two, three)
      }
      assert(down == TestApply.some((4, 1, 2, 3)))
    }
  }
}
