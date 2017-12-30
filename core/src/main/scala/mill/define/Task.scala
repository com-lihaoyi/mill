package mill.define

import ammonite.main.Router.Overrides
import mill.define.Applicative.Applyable
import mill.eval.{PathRef, Result}
import mill.util.Ctx
import upickle.default.{ReadWriter => RW, Reader => R, Writer => W}

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

trait NamedTask[+T] extends Task[T]{
  def owner: Task.Module
  def name: String
  def overrides: Int
}
trait Target[+T] extends NamedTask[T]{
  override def asTarget = Some(this)
  def enclosing: String
  def readWrite: RW[_]
}

object Target extends TargetGenerated with Applicative.Applyer[Task, Task, Result, Ctx] {

  implicit def apply[T](t: T)
                       (implicit r: R[T],
                        w: W[T],
                        e: sourcecode.Enclosing,
                        n: sourcecode.Name,
                        cl: Caller[mill.define.Task.Module],
                        o: Overrides): Target[T] = macro targetImpl[T]

  def targetImpl[T: c.WeakTypeTag](c: Context)
                                  (t: c.Expr[T])
                                  (r: c.Expr[R[T]],
                                   w: c.Expr[W[T]],
                                   e: c.Expr[sourcecode.Enclosing],
                                   n: c.Expr[sourcecode.Name],
                                   cl: c.Expr[Caller[mill.define.Task.Module]],
                                   o: c.Expr[Overrides]): c.Expr[Target[T]] = {
    import c.universe._
    c.Expr[Target[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[TargetImpl[T]]}(${Applicative.impl0[Task, T, Ctx](c)(q"mill.eval.Result.Success($t)").tree}, $e.value, $cl.value, $n.value, upickle.default.ReadWriter($w.write, $r.read), $o.value)"
      )
    )
  }

  implicit def apply[T](t: Result[T])
                       (implicit r: R[T],
                        w: W[T],
                        e: sourcecode.Enclosing,
                        n: sourcecode.Name,
                        cl: Caller[mill.define.Task.Module],
                        o: Overrides): Target[T] = macro targetResultImpl[T]

  def targetResultImpl[T: c.WeakTypeTag](c: Context)
                                        (t: c.Expr[Result[T]])
                                        (r: c.Expr[R[T]],
                                         w: c.Expr[W[T]],
                                         e: c.Expr[sourcecode.Enclosing],
                                         n: c.Expr[sourcecode.Name],
                                         cl: c.Expr[Caller[mill.define.Task.Module]],
                                         o: c.Expr[Overrides]): c.Expr[Target[T]] = {
    import c.universe._
    c.Expr[Target[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[TargetImpl[T]]}(${Applicative.impl0[Task, T, Ctx](c)(t.tree).tree}, $e.value, $cl.value, $n.value, upickle.default.ReadWriter($w.write, $r.read), $o.value)"
      )
    )
  }

  def apply[T](t: Task[T])
              (implicit r: R[T],
               w: W[T],
               e: sourcecode.Enclosing,
               n: sourcecode.Name,
               cl: Caller[mill.define.Task.Module],
               o: Overrides): Target[T] = macro targetTaskImpl[T]

  def targetTaskImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[Task[T]])
                                      (r: c.Expr[R[T]],
                                       w: c.Expr[W[T]],
                                       e: c.Expr[sourcecode.Enclosing],
                                       n: c.Expr[sourcecode.Name],
                                       cl: c.Expr[Caller[mill.define.Task.Module]],
                                       o: c.Expr[Overrides]): c.Expr[Target[T]] = {
    import c.universe._
    c.Expr[Target[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[TargetImpl[T]]}($t, $e.value, $cl.value, $n.value, upickle.default.ReadWriter($w.write, $r.read), $o.value)"
      )
    )
  }

  def command[T](t: Result[T])
                (implicit w: W[T],
                 n: sourcecode.Name,
                 cl: Caller[mill.define.Task.Module],
                 o: Overrides): Command[T] = macro commandImpl[T]

  def source(path: ammonite.ops.Path) = new Source(path)

  def command[T](t: Task[T])
                (implicit c: Caller[Task.Module],
                 n: sourcecode.Name,
                 w: W[T],
                 o: Overrides): Command[T] = new Command(t, c.value, n.value, w, o.value)

  def commandImpl[T: c.WeakTypeTag](c: Context)
                                   (t: c.Expr[T])
                                   (w: c.Expr[W[T]],
                                    n: c.Expr[sourcecode.Name],
                                    cl: c.Expr[Caller[mill.define.Task.Module]],
                                    o: c.Expr[Overrides]): c.Expr[Command[T]] = {
    import c.universe._

    c.Expr[Command[T]](
      q"new ${weakTypeOf[Command[T]]}(${Applicative.impl[Task, T, Ctx](c)(t).tree}, $cl.value, $n.value, $w, $o.value)"
    )
  }

  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, Ctx]

  def persistent[T](t: Result[T])(implicit r: R[T],
                                  w: W[T],
                                  e: sourcecode.Enclosing,
                                  n: sourcecode.Name,
                                  cl: Caller[mill.define.Task.Module],
                                  o: Overrides): Target[T] = macro persistentImpl[T]

  def persistentImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[T])
                                      (r: c.Expr[R[T]],
                                       w: c.Expr[W[T]],
                                       e: c.Expr[sourcecode.Enclosing],
                                       n: c.Expr[sourcecode.Name],
                                       cl: c.Expr[Caller[mill.define.Task.Module]],
                                       o: c.Expr[Overrides]): c.Expr[Persistent[T]] = {
    import c.universe._

    c.Expr[Persistent[T]](
      mill.plugin.Cacher.wrapCached(c)(
        q"new ${weakTypeOf[Persistent[T]]}(${Applicative.impl[Task, T, Ctx](c)(t).tree}, $e.value, $cl.value, $n.value, upickle.default.ReadWriter($w.write, $r.read), $o.value)"
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

case class Caller[A](value: A)
object Caller {
  def apply[T]()(implicit c: Caller[T]) = c.value
  implicit def generate[T]: Caller[T] = macro impl[T]
  def impl[T: c.WeakTypeTag](c: Context): c.Tree = {
    import c.universe._
    q"new _root_.mill.define.Caller[${weakTypeOf[T]}](this)"
  }
}

class TargetImpl[+T](t: Task[T],
                     val enclosing: String,
                     val owner: Task.Module,
                     val name: String,
                     val readWrite: RW[_],
                     val overrides: Int) extends Target[T] {
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def toString = enclosing + "@" + Integer.toHexString(System.identityHashCode(this))
}
class Command[+T](t: Task[T],
                  val owner: Task.Module,
                  val name: String,
                  val writer: W[_],
                  val overrides: Int) extends NamedTask[T] {
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def asCommand = Some(this)
}
class Persistent[+T](t: Task[T],
                     enclosing: String,
                     owner: Task.Module,
                     name: String,
                     readWrite: RW[_],
                     overrides: Int)
  extends TargetImpl[T](t, enclosing, owner, name, readWrite, overrides) {
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
