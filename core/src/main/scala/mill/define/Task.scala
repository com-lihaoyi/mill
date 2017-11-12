package mill.define

import mill.define.Applicative.Applyable
import mill.eval.PathRef
import mill.util.Args
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

abstract class Task[+T] extends Task.Ops[T] with Applyable[T]{
  /**
    * What other Targets does this Target depend on?
    */
  val inputs: Seq[Task[_]]

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

trait Target[+T] extends Task[T]
class TargetImpl[+T](t: Task[T], enclosing: String) extends Target[T] {
  val inputs = Seq(t)
  def evaluate(args: Args) = args[T](0)
  override def toString = enclosing + "@" + Integer.toHexString(System.identityHashCode(this))
}
class Command[+T](t: Task[T]) extends Task[T] {
  val inputs = Seq(t)
  def evaluate(args: Args) = args[T](0)
}
object Task extends Applicative.Applyer[Task, Task, Args]{

  def underlying[A](v: Task[A]) = v

  trait Cacher extends mill.define.Cacher[Task, Target]{
    def wrapCached[T](t: Task[T], enclosing: String): Target[T] = new TargetImpl(t, enclosing)
  }
  class Task0[T](t: T) extends Task[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: Args)  = t0
  }

  def apply[T](t: T): Target[T] = macro targetCachedImpl[T]
  def apply[T](t: Task[T]): Target[T] = macro Cacher.impl0[Task, T]

  def command[T](t: T): Command[T] = macro targetCommandImpl[T]
  def command[T](t: Task[T]): Command[T] = new Command(t)

  def task[T](t: T): Task[T] = macro Applicative.impl[Task, T, Args]
  def task[T](t: Task[T]): Task[T] = t


  def targetCommandImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Command[T]] = {
    import c.universe._

    c.Expr[Command[T]](
      q"new ${weakTypeOf[Command[T]]}(${Applicative.impl[Task, T, Args](c)(t).tree})"
    )
  }
  def targetCachedImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Target[T]] = {
    c.Expr[Target[T]](
      mill.define.Cacher.wrapCached(c)(
        Applicative.impl[Task, T, Args](c)(t).tree
      )
    )
  }

  abstract class Ops[+T]{ this: Task[T] =>
    def map[V](f: T => V) = new Task.Mapped(this, f)
    def mapDest[V](f: (T, Args) => V) = new Task.MappedDest(this, f)

    def filter(f: T => Boolean) = this
    def withFilter(f: T => Boolean) = this
    def zip[V](other: Task[V]) = new Task.Zipped(this, other)

  }

  def traverse[T](source: Seq[Task[T]]) = {
    new Traverse[T](source)
  }
  class Traverse[+T](inputs0: Seq[Task[T]]) extends Task[Seq[T]]{
    val inputs = inputs0
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  class MappedDest[+T, +V](source: Task[T], f: (T, Args) => V) extends Task[V]{
    def evaluate(args: Args) = f(args(0), args)
    val inputs = List(source)
  }
  class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)]{
    def evaluate(args: Args) = (args(0), args(1))
    val inputs = List(source1, source2)
  }

  def path(path: ammonite.ops.Path) = new Path(path)
  class Path(path: ammonite.ops.Path) extends Task[PathRef]{
    def handle = PathRef(path)
    def evaluate(args: Args) = handle
    override def sideHash = handle.hashCode()
    val inputs = Nil
  }


  def mapCtx[A, B](t: Task[A])(f: (A, Args) => B) = t.mapDest(f)
  def zip() =  new Task.Task0(())
  def zip[A](a: Task[A]) = a.map(Tuple1(_))
  def zip[A, B](a: Task[A], b: Task[B]) = a.zip(b)
  def zip[A, B, C](a: Task[A], b: Task[B], c: Task[C]) = new Task[(A, B, C)]{
    val inputs = Seq(a, b, c)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2))
  }
  def zip[A, B, C, D](a: Task[A], b: Task[B], c: Task[C], d: Task[D]) = new Task[(A, B, C, D)]{
    val inputs = Seq(a, b, c, d)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3))
  }
  def zip[A, B, C, D, E](a: Task[A], b: Task[B], c: Task[C], d: Task[D], e: Task[E]) = new Task[(A, B, C, D, E)]{
    val inputs = Seq(a, b, c, d, e)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4))
  }
  def zip[A, B, C, D, E, F](a: Task[A], b: Task[B], c: Task[C], d: Task[D], e: Task[E], f: Task[F]) = new Task[(A, B, C, D, E, F)]{
    val inputs = Seq(a, b, c, d, e, f)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4), args[F](5))
  }
  def zip[A, B, C, D, E, F, G](a: Task[A], b: Task[B], c: Task[C], d: Task[D], e: Task[E], f: Task[F], g: Task[G]) = new Task[(A, B, C, D, E, F, G)]{
    val inputs = Seq(a, b, c, d, e, f, g)
    def evaluate(args: Args) = (args[A](0), args[B](1), args[C](2), args[D](3), args[E](4), args[F](5), args[G](6))
  }
}
