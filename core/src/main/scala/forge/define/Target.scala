package forge.define

import ammonite.ops.{CommandResult, mkdir}
import forge.define.Applicative.Applyable
import forge.eval.PathRef
import forge.util.{Args, JsonFormatters}
import play.api.libs.json.{Format, Json}

import scala.annotation.compileTimeOnly
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

abstract class Target[+T] extends Target.Ops[T] with Applyable[T]{
  /**
    * What other Targets does this Target depend on?
    */
  val inputs: Seq[Target[_]]

  /**
    * Evaluate this target
    */
  def evaluate(args: Args): T

  /**
    * Even if this target's inputs did not change, does it need to re-evaluate
    * anyway?
    */
  def sideHash: Int = 0
}


object Target extends Applicative.Applyer[Target, Target, Args]{
  def underlying[A](v: Target[A]) = v

  type Cacher = forge.define.Cacher[Target[_]]
  class Target0[T](t: T) extends Target[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: Args)  = t0
  }
  def apply[T](t: Target[T]): Target[T] = macro forge.define.Cacher.impl0[Target, T]
  def command[T](t: T): Target[T] = macro Applicative.impl[Target, T, Args]
  def apply[T](t: T): Target[T] = macro impl[Target, T, Args]
  def impl[M[_], T: c.WeakTypeTag, Ctx: c.WeakTypeTag]
          (c: Context)
          (t: c.Expr[T])
          (implicit tt: c.WeakTypeTag[M[_]]): c.Expr[M[T]] = {
    forge.define.Cacher.wrapCached[M, T](c)(
      Applicative.impl[M, T, Ctx](c)(t)
    )
  }

  abstract class Ops[+T]{ this: Target[T] =>
    def map[V](f: T => V) = new Target.Mapped(this, f)
    def mapDest[V](f: (T, Args) => V) = new Target.MappedDest(this, f)

    def filter(f: T => Boolean) = this
    def withFilter(f: T => Boolean) = this
    def zip[V](other: Target[V]) = new Target.Zipped(this, other)

  }

  def traverse[T](source: Seq[Target[T]]) = {
    new Traverse[T](source)
  }
  class Traverse[+T](val inputs: Seq[Target[T]]) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[+T, +V](source: Target[T], f: T => V) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  class MappedDest[+T, +V](source: Target[T], f: (T, Args) => V) extends Target[V]{
    def evaluate(args: Args) = f(args(0), args)
    val inputs = List(source)
  }
  class Zipped[+T, +V](source1: Target[T], source2: Target[V]) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(1))
    val inputs = List(source1, source2)
  }

  def path(path: ammonite.ops.Path) = new Path(path)
  class Path(path: ammonite.ops.Path) extends Target[PathRef]{
    def handle = PathRef(path)
    def evaluate(args: Args) = handle
    override def sideHash = handle.hashCode()
    val inputs = Nil
  }


  def mapCtx[A, B](t: Target[A])(f: (A, Args) => B) = t.mapDest(f)
  def zip() =  new Target.Target0(())
  def zip[A](a: Target[A]) = a.map(Tuple1(_))
  def zip[A, B](a: Target[A], b: Target[B]) = a.zip(b)
  def zip[A, B, C](a: Target[A], b: Target[B], c: Target[C]) = new Target[(A, B, C)]{
    val inputs = Seq(a, b, c)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2))
  }
  def zip[A, B, C, D](a: Target[A], b: Target[B], c: Target[C], d: Target[D]) = new Target[(A, B, C, D)]{
    val inputs = Seq(a, b, c, d)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3))
  }
  def zip[A, B, C, D, E](a: Target[A], b: Target[B], c: Target[C], d: Target[D], e: Target[E]) = new Target[(A, B, C, D, E)]{
    val inputs = Seq(a, b, c, d, e)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4))
  }
  def zip[A, B, C, D, E, F](a: Target[A], b: Target[B], c: Target[C], d: Target[D], e: Target[E], f: Target[F]) = new Target[(A, B, C, D, E, F)]{
    val inputs = Seq(a, b, c, d, e, f)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4), args[F](5))
  }
  def zip[A, B, C, D, E, F, G](a: Target[A], b: Target[B], c: Target[C], d: Target[D], e: Target[E], f: Target[F], g: Target[G]) = new Target[(A, B, C, D, E, F, G)]{
    val inputs = Seq(a, b, c, d, e, f, g)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4), args[F](5), args[G](6))
  }
}
