package mill.define

import mill.define.Applicative.Applyable
import mill.eval.{PathRef, Result}
import mill.util.Ctx

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

abstract class Task[+T] extends Task.Ops[T] with Applyable[Task, T]{
  /**
    * What other Targets does this Target depend on?
    */
  val inputs: Seq[Task[_]]

  /**
    * Evaluate this target
    */
  def evaluate(args: Ctx): Result[T]

  /**
    * Even if this target's inputs did not change, does it need to re-evaluate
    * anyway?
    */
  def sideHash: Int = 0

  def flushDest: Boolean = true

  def asTarget: Option[Target[T]] = None
  def asCommand: Option[Command[T]] = None
  def asPersistent: Option[Persistent[T]] = None
  def self = this
}

trait Target[+T] extends Task[T]{
  override def asTarget = Some(this)
}

object Target extends TargetGenerated with Applicative.Applyer[Task, Task, Result, Ctx] {

  implicit def apply[T](t: T): Target[T] = macro targetImpl[T]

  implicit def apply[T](t: Result[T]): Target[T] = macro targetResultImpl[T]

  def apply[T](t: Task[T]): Target[T] = macro targetTaskImpl[T]

  def command[T](t: Result[T]): Command[T] = macro commandImpl[T]

  def source(path: ammonite.ops.Path) = new Source(path)

  def command[T](t: Task[T]): Command[T] = new Command(t)

  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, Ctx]
  def task[T](t: Task[T]): Task[T] = t

  def persistent[T](t: Result[T]): Target[T] = macro persistentImpl[T]
  def persistentImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Persistent[T]] = {
    import c.universe._

    c.Expr[Persistent[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[Persistent[T]]}(${Applicative.impl[Task, T, Ctx](c)(t).tree})"
      )
    )
  }
  def commandImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Command[T]] = {
    import c.universe._

    c.Expr[Command[T]](
      q"new ${weakTypeOf[Command[T]]}(${Applicative.impl[Task, T, Ctx](c)(t).tree})"
    )
  }

  def targetTaskImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Task[T]]): c.Expr[Target[T]] = {
    import c.universe._
    c.Expr[Target[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[TargetImpl[T]]}($t, _root_.sourcecode.Enclosing())"
      )
    )
  }
  def targetImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Target[T]] = {
    import c.universe._
    c.Expr[Target[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[TargetImpl[T]]}(${Applicative.impl0[Task, T, Ctx](c)(q"mill.eval.Result.Success($t)").tree}, _root_.sourcecode.Enclosing())"
      )
    )
  }

  def targetResultImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Result[T]]): c.Expr[Target[T]] = {
    import c.universe._
    c.Expr[Target[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[TargetImpl[T]]}(${Applicative.impl0[Task, T, Ctx](c)(t.tree).tree}, _root_.sourcecode.Enclosing())"
      )
    )
  }

  type TT[+X] = Task[X]
  def makeT[X](inputs0: Seq[TT[_]], evaluate0: Ctx => Result[X]) = new Task[X] {
    val inputs = inputs0
    def evaluate(x: Ctx) = evaluate0(x)
  }

  def underlying[A](v: Task[A]) = v
  def mapCtx[A, B](t: Task[A])(f: (A, Ctx) => Result[B]) = t.mapDest(f)
  def zip() =  new Task.Task0(())
  def zip[A](a: Task[A]) = a.map(Tuple1(_))
  def zip[A, B](a: Task[A], b: Task[B]) = a.zip(b)
}
class TargetImpl[+T](t: Task[T], enclosing: String) extends Target[T] {
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def toString = enclosing + "@" + Integer.toHexString(System.identityHashCode(this))
}
class Command[+T](t: Task[T]) extends Task[T] {
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def asCommand = Some(this)
}
class Persistent[+T](t: Task[T]) extends Target[T] {
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def flushDest = false
  override def asPersistent = Some(this)
}
object Source{
  implicit def apply(p: ammonite.ops.Path) = new Source(p)
}
class Source(path: ammonite.ops.Path) extends Task[PathRef]{
  def handle = PathRef(path)
  def evaluate(args: Ctx) = handle
  override def sideHash = handle.hashCode()
  val inputs = Nil
}

object Task {


  trait TaskModule extends Module {
    def defaultCommandName(): String
  }
  trait Module extends mill.plugin.Cacher[Target]{
    def wrapCached[T](t: Target[T], enclosing: String): Target[T] = t
  }

  class Task0[T](t: T) extends Task[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: Ctx)  = t0
  }

  abstract class Ops[+T]{ this: Task[T] =>
    def map[V](f: T => V) = new Task.Mapped(this, f)
    def mapDest[V](f: (T, Ctx) => Result[V]) = new Task.MappedDest(this, f)

    def filter(f: T => Boolean) = this
    def withFilter(f: T => Boolean) = this
    def zip[V](other: Task[V]) = new Task.Zipped(this, other)

  }

  def traverse[T, V](source: Seq[T])(f: T => Task[V]) = {
    new Sequence[V](source.map(f))
  }
  def sequence[T](source: Seq[Task[T]]) = new Sequence[T](source)

  class Sequence[+T](inputs0: Seq[Task[T]]) extends Task[Seq[T]]{
    val inputs = inputs0
    def evaluate(args: Ctx) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V]{
    def evaluate(args: Ctx) = f(args(0))
    val inputs = List(source)
  }
  class MappedDest[+T, +V](source: Task[T], f: (T, Ctx) => Result[V]) extends Task[V]{
    def evaluate(args: Ctx) = f(args(0), args)
    val inputs = List(source)
  }
  class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)]{
    def evaluate(args: Ctx) = (args(0), args(1))
    val inputs = List(source1, source2)
  }
}
