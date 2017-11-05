package forge

import forge.util.Args

object ZipTarget
trait ZipTarget {
  val T = Target
  type T[V] = Target[V]
  def zipMap[R]()(f: () => R) = new Target.Target0(f())
  def zipMap[A, R](a: T[A])(f: A => R) = a.map(f)
  def zipMap[A, B, R](a: T[A], b: T[B])(f: (A, B) => R) = zip(a, b).map(f.tupled)
  def zipMap[A, B, C, R](a: T[A], b: T[B], c: T[C])(f: (A, B, C) => R) = zip(a, b, c).map(f.tupled)
  def zipMap[A, B, C, D, R](a: T[A], b: T[B], c: T[C], d: T[D])(f: (A, B, C, D) => R) = zip(a, b, c, d).map(f.tupled)
  def zipMap[A, B, C, D, E, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E])(f: (A, B, C, D, E) => R) = zip(a, b, c, d, e).map(f.tupled)
  def zipMap[A, B, C, D, E, F, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F])(cb: (A, B, C, D, E, F) => R) = zip(a, b, c, d, e, f).map(cb.tupled)
  def zipMap[A, B, C, D, E, F, G, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F], g: T[G])(cb: (A, B, C, D, E, F, G) => R) = zip(a, b, c, d, e, f, g).map(cb.tupled)
  def zip() =  new Target.Target0(())
  def zip[A](a: T[A]) = a.map(Tuple1(_))
  def zip[A, B](a: T[A], b: T[B]) = a.zip(b)
  def zip[A, B, C](a: T[A], b: T[B], c: T[C]) = new T[(A, B, C)]{
    val inputs = Seq(a, b, c)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2))
  }
  def zip[A, B, C, D](a: T[A], b: T[B], c: T[C], d: T[D]) = new T[(A, B, C, D)]{
    val inputs = Seq(a, b, c, d)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3))
  }
  def zip[A, B, C, D, E](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E]) = new T[(A, B, C, D, E)]{
    val inputs = Seq(a, b, c, d, e)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4))
  }
  def zip[A, B, C, D, E, F](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F]) = new T[(A, B, C, D, E, F)]{
    val inputs = Seq(a, b, c, d, e, f)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4), args[F](5))
  }
  def zip[A, B, C, D, E, F, G](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F], g: T[G]) = new T[(A, B, C, D, E, F, G)]{
    val inputs = Seq(a, b, c, d, e, f, g)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4), args[F](5), args[G](6))
  }
}
