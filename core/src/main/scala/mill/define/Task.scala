package mill.define

import mill.define.Applicative.Applyable
import mill.eval.{PathRef, Result}
import mill.util.Ctx
import upickle.default.{ReadWriter => RW, Reader => R, Writer => W}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

/**
  * Models a single node in the Mill build graph, with a list of inputs and a
  * single output of type [[T]].
  *
  * Generally not instantiated manually, but instead constructed via the
  * [[Target.apply]] & similar macros.
  */
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
  def ctx: Module.Ctx
}
trait Target[+T] extends NamedTask[T]{
  override def asTarget = Some(this)
  def readWrite: RW[_]
}

object Target extends TargetGenerated with Applicative.Applyer[Task, Task, Result, Ctx] {

  implicit def apply[T](t: T)
                       (implicit r: R[T],
                        w: W[T],
                        ctx: Module.Ctx): Target[T] = macro targetImpl[T]

  def targetImpl[T: c.WeakTypeTag](c: Context)
                                  (t: c.Expr[T])
                                  (r: c.Expr[R[T]],
                                   w: c.Expr[W[T]],
                                   ctx: c.Expr[Module.Ctx]): c.Expr[Target[T]] = {
    import c.universe._
    val lhs = Applicative.impl0[Task, T, Ctx](c)(reify(Result.Success(t.splice)).tree)

    mill.moduledefs.Cacher.impl0[TargetImpl[T]](c)(
      reify(
        new TargetImpl[T](lhs.splice, ctx.splice, RW(w.splice.write, r.splice.read))
      )
    )
  }

  implicit def apply[T](t: Result[T])
                       (implicit r: R[T],
                        w: W[T],
                        ctx: Module.Ctx): Target[T] = macro targetResultImpl[T]

  def targetResultImpl[T: c.WeakTypeTag](c: Context)
                                        (t: c.Expr[Result[T]])
                                        (r: c.Expr[R[T]],
                                         w: c.Expr[W[T]],
                                         ctx: c.Expr[Module.Ctx]): c.Expr[Target[T]] = {
    import c.universe._
    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          Applicative.impl0[Task, T, Ctx](c)(t.tree).splice,
          ctx.splice,
          RW(w.splice.write, r.splice.read)
        )
      )
    )
  }

  def apply[T](t: Task[T])
              (implicit r: R[T],
               w: W[T],
               ctx: Module.Ctx): Target[T] = macro targetTaskImpl[T]

  def targetTaskImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[Task[T]])
                                      (r: c.Expr[R[T]],
                                       w: c.Expr[W[T]],
                                       ctx: c.Expr[Module.Ctx]): c.Expr[Target[T]] = {
    import c.universe._
    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](t.splice, ctx.splice, RW(w.splice.write, r.splice.read))
      )
    )
  }

  def source(value: Result[ammonite.ops.Path])
            (implicit r: R[PathRef],
             w: W[PathRef],
             ctx: Module.Ctx): Input[PathRef] = macro sourceImpl

  def sourceImpl(c: Context)
                (value: c.Expr[Result[ammonite.ops.Path]])
                (r: c.Expr[R[PathRef]],
                 w: c.Expr[W[PathRef]],
                 ctx: c.Expr[Module.Ctx]): c.Expr[Input[PathRef]] = {
    import c.universe._
    val wrapped: c.Expr[Result[PathRef]] = reify(value.splice match{
      case Result.Success(p) => Result.Success(PathRef(p))
      case x: Result.Failing => x
    })
    mill.moduledefs.Cacher.impl0[Input[PathRef]](c)(
      reify(
        new Input[PathRef](
          Applicative.impl0[Task, PathRef, Ctx](c)(wrapped.tree).splice,
          ctx.splice,
          RW(w.splice.write, r.splice.read),
        )
      )
    )
  }

  def input[T](value: Result[T])
              (implicit r: R[T],
                w: W[T],
                ctx: Module.Ctx): Input[T] = macro inputImpl[T]

  def inputImpl[T: c.WeakTypeTag](c: Context)
                                  (value: c.Expr[T])
                                  (r: c.Expr[R[T]],
                                   w: c.Expr[W[T]],
                                   ctx: c.Expr[Module.Ctx]): c.Expr[Input[T]] = {
    import c.universe._

    mill.moduledefs.Cacher.impl0[Input[T]](c)(
      reify(
        new Input[T](
          Applicative.impl[Task, T, Ctx](c)(value).splice,
          ctx.splice,
          RW(w.splice.write, r.splice.read)
        )
      )
    )
  }

  def command[T](t: Task[T])
                (implicit ctx: Module.Ctx,
                 w: W[T]): Command[T] = new Command(t, ctx, w)

  def command[T](t: Result[T])
                (implicit w: W[T],
                 ctx: Module.Ctx): Command[T] = macro commandImpl[T]

  def commandImpl[T: c.WeakTypeTag](c: Context)
                                   (t: c.Expr[T])
                                   (w: c.Expr[W[T]],
                                    ctx: c.Expr[Module.Ctx]): c.Expr[Command[T]] = {
    import c.universe._
    reify(
      new Command[T](Applicative.impl[Task, T, Ctx](c)(t).splice, ctx.splice, w.splice)
    )
  }

  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, Ctx]

  def persistent[T](t: Result[T])(implicit r: R[T],
                                  w: W[T],
                                  ctx: Module.Ctx): Target[T] = macro persistentImpl[T]

  def persistentImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[T])
                                      (r: c.Expr[R[T]],
                                       w: c.Expr[W[T]],
                                       ctx: c.Expr[Module.Ctx]): c.Expr[Persistent[T]] = {
    import c.universe._


    mill.moduledefs.Cacher.impl0[Persistent[T]](c)(
      reify(
        new Persistent[T](
          Applicative.impl[Task, T, Ctx](c)(t).splice,
          ctx.splice,
          RW(w.splice.write, r.splice.read)
        )
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
                     ctx0: Module.Ctx,
                     val readWrite: RW[_]) extends Target[T] {
  val ctx = ctx0.copy(segments0 = ctx0.segments0 ++ Seq(ctx0.segment))
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def toString = ctx.enclosing + "@" + Integer.toHexString(System.identityHashCode(this))
}
class Command[+T](t: Task[T],
                  ctx0: Module.Ctx,
                  val writer: W[_]) extends NamedTask[T] {
  val ctx = ctx0.copy(segments0 = ctx0.segments0 ++ Seq(ctx0.segment))
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def asCommand = Some(this)
}
class Persistent[+T](t: Task[T],
                     ctx0: Module.Ctx,
                     readWrite: RW[_])
  extends TargetImpl[T](t, ctx0, readWrite) {

  override def flushDest = false
  override def asPersistent = Some(this)
}
class Input[T](t: Task[T],
               ctx0: Module.Ctx,
               val readWrite: RW[_]) extends Target[T]{
  val ctx = ctx0.copy(segments0 = ctx0.segments0 ++ Seq(ctx0.segment))
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def sideHash = util.Random.nextInt()
}

object Task {

  trait TaskModule extends Module {
    def defaultCommandName(): String
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
