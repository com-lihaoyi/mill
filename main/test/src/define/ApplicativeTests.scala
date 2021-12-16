package mill.define

import mill.api.Ctx.ImplicitStub
import utest._

import scala.annotation.compileTimeOnly
import scala.language.experimental.macros

object ApplicativeTests extends TestSuite {
  implicit def optionToOpt[T](o: Option[T]): Opt[T] = new Opt(o)
  class Opt[+T](val self: Option[T]) extends Applicative.Applyable[Option, T]
  object Opt extends Applicative.Applyer[Opt, Option, Applicative.Id, String] {

    val injectedCtx = "helloooo"
    def apply[T](t: T): Option[T] = macro Applicative.impl[Option, T, String]

    def traverseCtx[I, R](xs: Seq[Opt[I]])(f: (IndexedSeq[I], String) => Applicative.Id[R]): Option[R] = {
      if (xs.exists(_.self.isEmpty)) None
      else Some(f(xs.map(_.self.get).toVector, injectedCtx))
    }
  }
  class Counter {
    var value = 0
    def apply() = {
      value += 1
      value
    }
  }
  @compileTimeOnly("Target.ctx() can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: String = ???

  val tests = Tests {

    "selfContained" - {

      "simple" - assert(Opt("lol " + 1) == Some("lol 1"))
      "singleSome" - assert(Opt("lol " + Some("hello")()) == Some("lol hello"))
      "twoSomes" - assert(Opt(Some("lol ")() + Some("hello")()) == Some("lol hello"))
      "singleNone" - assert(Opt("lol " + None()) == None)
      "twoNones" - assert(Opt("lol " + None() + None()) == None)
      "moreThan22" - {
        assert(
          Opt(
            "lol " +
              None() + None() + None() + None() + None() +
              None() + None() + None() + None() + Some(" world")() +
              None() + None() + None() + None() + None() +
              None() + None() + None() + None() + None() +
              None() + None() + None() + None() + Some(" moo")()
          ) == None
        )
        assert(
          Opt(
            "lol " +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")() +
            Some("a")() + Some("b")() + Some("c")() + Some("d")() + Some("e")()
          ) == Some("lol abcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcde")
        )
      }
    }
    "context" - {
      assert(Opt(Opt.ctx() + Some("World")()) == Some("hellooooWorld"))
    }
    "capturing" - {
      val lol = "lol "
      def hell(o: String) = "hell" + o
      "simple" - assert(Opt(lol + 1) == Some("lol 1"))
      "singleSome" - assert(Opt(lol + Some(hell("o"))()) == Some("lol hello"))
      "twoSomes" - assert(Opt(Some(lol)() + Some(hell("o"))()) == Some("lol hello"))
      "singleNone" - assert(Opt(lol + None()) == None)
      "twoNones" - assert(Opt(lol + None() + None()) == None)
    }
    "allowedLocalDef" - {
      // Although x is defined inside the Opt{...} block, it is also defined
      // within the LHS of the Applyable#apply call, so it is safe to life it
      // out into the `zipMap` arguments list.
      val res = Opt { "lol " + Some("hello").flatMap(x => Some(x)).apply() }
      assert(res == Some("lol hello"))
    }
    "upstreamAlwaysEvaluated" - {
      // Whether or not control-flow reaches the Applyable#apply call inside an
      // Opt{...} block, we always evaluate the LHS of the Applyable#apply
      // because it gets lifted out of any control flow statements
      val counter = new Counter()
      def up = Opt { "lol " + counter() }
      val down = Opt { if ("lol".length > 10) up() else "fail" }
      assert(
        down == Some("fail"),
        counter.value == 1
      )
    }
    "upstreamEvaluatedOnlyOnce" - {
      // Even if control-flow reaches the Applyable#apply call more than once,
      // it only gets evaluated once due to its lifting out of the Opt{...} block
      val counter = new Counter()
      def up = Opt { "lol " + counter() }
      def runTwice[T](t: => T) = (t, t)
      val down = Opt { runTwice(up()) }
      assert(
        down == Some(("lol 1", "lol 1")),
        counter.value == 1
      )
    }
    "evaluationsInsideLambdasWork" - {
      // This required some fiddling with owner chains inside the macro to get
      // working, so ensure it doesn't regress
      val counter = new Counter()
      def up = Opt { "hello" + counter() }
      val down1 = Opt { (() => up())() }
      val down2 = Opt { Seq(1, 2, 3).map(n => up() * n) }
      assert(
        down1 == Some("hello1"),
        down2 == Some(Seq("hello2", "hello2hello2", "hello2hello2hello2"))
      )
    }
    "appliesEvaluatedOncePerLexicalCallsite" - {
      // If you have multiple Applyable#apply() lexically in the source code of
      // your Opt{...} call, each one gets evaluated once, even if the LHS of each
      // apply() call is identical. It's up to the downstream zipMap()
      // implementation to decide if it wants to dedup them or do other things.
      val counter = new Counter()
      def up = Opt { "hello" + counter() }
      val down = Opt { Seq(1, 2, 3).map(n => n + up() + up()) }
      assert(down == Some(Seq("1hello1hello2", "2hello1hello2", "3hello1hello2")))
    }
    "appliesEvaluateBeforehand" - {
      // Every Applyable#apply() within a Opt{...} block evaluates before any
      // other logic within that block, even if they would happen first in the
      // normal Scala evaluation order
      val counter = new Counter()
      def up = Opt { counter() }
      val down = Opt {
        val res = counter()
        val one = up()
        val two = up()
        val three = up()
        (res, one, two, three)
      }
      assert(down == Some((4, 1, 2, 3)))
    }
  }
}
