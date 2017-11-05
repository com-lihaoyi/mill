package forge
import forge.define.{Applicative, Target}
import utest._
import language.experimental.macros

object ApplicativeMacrosTests extends TestSuite /*with define.Applicative.Applyer[Option]*/ {

//  def apply[T](t: Option[T]): Option[T] = macro Applicative.impl0[Option, T]
//  def apply[T](t: T): Option[T] = macro Applicative.impl[Option, T]
//
//  type O[+T] = Option[T]
//  def map[A, B](a: O[A], f: A => B) = a.map(f)
//  def zip() = Some(())
//  def zip[A](a: O[A]) = a.map(Tuple1(_))
//  def zip[A, B](a: O[A], b: O[B]) = {
//    for(a <- a; b <- b) yield (a, b)
//  }
//  def zip[A, B, C](a: O[A], b: O[B], c: O[C]) = {
//    for(a <- a; b <- b; c <- c) yield (a, b, c)
//  }
//  def zip[A, B, C, D](a: O[A], b: O[B], c: O[C], d: O[D]) = {
//    for(a <- a; b <- b; c <- c; d <- d) yield (a, b, c, d)
//  }
//  def zip[A, B, C, D, E](a: O[A], b: O[B], c: O[C], d: O[D], e: O[E]) = {
//    for(a <- a; b <- b; c <- c; d <- d; e <- e) yield (a, b, c, d, e)
//  }
//  def zip[A, B, C, D, E, F](a: O[A], b: O[B], c: O[C], d: O[D], e: O[E], f: O[F]) ={
//    for(a <- a; b <- b; c <- c; d <- d; e <- e; f <- f) yield (a, b, c, d, e, f)
//  }
//  def zip[A, B, C, D, E, F, G](a: O[A], b: O[B], c: O[C], d: O[D], e: O[E], f: O[F], g: O[G]) = {
//    for(a <- a; b <- b; c <- c; d <- d; e <- e; f <- f; g <- g) yield (a, b, c, d, e, f, g)
//  }
  val tests = Tests{
    'hello - {
//      assert(
//        apply("lol " + 1) == Some("lol 1"),
//        apply("lol " + None()) == None
//      )
    }
  }
}
