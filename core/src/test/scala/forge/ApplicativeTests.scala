package forge
import forge.define.Applicative
import utest._
import language.experimental.macros


object ApplicativeTests extends TestSuite {
  implicit def optionToOpt[T](o: Option[T]): Opt[T] = new Opt(o)
  class Opt[T](val o: Option[T]) extends Applicative.Applyable[T]
  object Opt extends define.Applicative.Applyer[Opt, Option]{

    def underlying[A](v: Opt[A]) = v.o
    def apply[T](t: Option[T]): Option[T] = macro Applicative.impl0[Option, T]
    def apply[T](t: T): Option[T] = macro Applicative.impl[Option, T]

    type O[+T] = Option[T]
    def map[A, B](a: O[A], f: A => B) = a.map(f)
    def zip() = Some(())
    def zip[A](a: O[A]) = a.map(Tuple1(_))
    def zip[A, B](a: O[A], b: O[B]) = {
      for(a <- a; b <- b) yield (a, b)
    }
    def zip[A, B, C](a: O[A], b: O[B], c: O[C]) = {
      for(a <- a; b <- b; c <- c) yield (a, b, c)
    }
    def zip[A, B, C, D](a: O[A], b: O[B], c: O[C], d: O[D]) = {
      for(a <- a; b <- b; c <- c; d <- d) yield (a, b, c, d)
    }
    def zip[A, B, C, D, E](a: O[A], b: O[B], c: O[C], d: O[D], e: O[E]) = {
      for(a <- a; b <- b; c <- c; d <- d; e <- e) yield (a, b, c, d, e)
    }
    def zip[A, B, C, D, E, F](a: O[A], b: O[B], c: O[C], d: O[D], e: O[E], f: O[F]) ={
      for(a <- a; b <- b; c <- c; d <- d; e <- e; f <- f) yield (a, b, c, d, e, f)
    }
    def zip[A, B, C, D, E, F, G](a: O[A], b: O[B], c: O[C], d: O[D], e: O[E], f: O[F], g: O[G]) = {
      for(a <- a; b <- b; c <- c; d <- d; e <- e; f <- f; g <- g) yield (a, b, c, d, e, f, g)
    }
  }
  class Counter{
    var value = 0
    def apply() = {
      value += 1
      value
    }
  }

  val tests = Tests{

    'selfContained - {

      'simple - assert(Opt("lol " + 1) == Some("lol 1"))
      'singleSome - assert(Opt("lol " + Some("hello")()) == Some("lol hello"))
      'twoSomes - assert(Opt(Some("lol ")() + Some("hello")()) == Some("lol hello"))
      'singleNone - assert(Opt("lol " + None()) == None)
      'twoNones - assert(Opt("lol " + None() + None()) == None)
    }
    'capturing - {
      val lol = "lol "
      def hell(o: String) = "hell" + o
      'simple - assert(Opt(lol + 1) == Some("lol 1"))
      'singleSome - assert(Opt(lol + Some(hell("o"))()) == Some("lol hello"))
      'twoSomes - assert(Opt(Some(lol)() + Some(hell("o"))()) == Some("lol hello"))
      'singleNone - assert(Opt(lol + None()) == None)
      'twoNones - assert(Opt(lol + None() + None()) == None)
    }
    'allowedLocalDef - {
      // Although x is defined inside the Opt{...} block, it is also defined
      // within the LHS of the Applyable#apply call, so it is safe to life it
      // out into the `zipMap` arguments list.
      val res = Opt{ "lol " + Some("hello").flatMap(x => Some(x)).apply() }
      assert(res == Some("lol hello"))
    }
    'upstreamAlwaysEvaluated - {
      // Whether or not control-flow reaches the Applyable#apply call inside an
      // Opt{...} block, we always evaluate the LHS of the Applyable#apply
      // because it gets lifted out of any control flow statements
      val counter = new Counter()
      def up = Opt{ "lol " + counter() }
      val down = Opt{ if ("lol".length > 10) up() else "fail" }
      assert(
        down == Some("fail"),
        counter.value == 1
      )
    }
    'upstreamEvaluatedOnlyOnce - {
      // Even if control-flow reaches the Applyable#apply call more than once,
      // it only gets evaluated once due to its lifting out of the Opt{...} block
      val counter = new Counter()
      def up = Opt{ "lol " + counter() }
      def runTwice[T](t: => T) = (t, t)
      val down = Opt{ runTwice(up()) }
      assert(
        down == Some(("lol 1", "lol 1")),
        counter.value == 1
      )
    }
    'evaluationsInsideLambdasWork - {
      // This required some fiddling with owner chains inside the macro to get
      // working, so ensure it doesn't regress
      val counter = new Counter()
      def up = Opt{ "hello" + counter() }
      val down1 = Opt{ (() => up())() }
      val down2 = Opt{ Seq(1, 2, 3).map(n => up() * n) }
      assert(
        down1 == Some("hello1"),
        down2 == Some(Seq("hello2", "hello2hello2", "hello2hello2hello2"))
      )
    }
  }
}

