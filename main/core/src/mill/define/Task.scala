package mill.define

import ammonite.main.Router.Overrides
import mill.define.Applicative.Applyable
import mill.eval.{PathRef, Result}
import mill.util.EnclosingClass
import sourcecode.Compat.Context
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
  def evaluate(args: mill.util.Ctx): Result[T]

  /**
    * Even if this target's inputs did not change, does it need to re-evaluate
    * anyway?
    */
  def sideHash: Int = 0

  def flushDest: Boolean = true

  def asTarget: Option[Target[T]] = None
  def asCommand: Option[Command[T]] = None
  def asWorker: Option[Worker[T]] = None
  def self = this
}

trait NamedTask[+T] extends Task[T]{
  def ctx: mill.define.Ctx
  def label = ctx.segment match{case Segment.Label(v) => v}
  override def toString = ctx.segments.render
}
trait Target[+T] extends NamedTask[T]{
  override def asTarget = Some(this)
  def readWrite: RW[_]
}

object Target extends TargetGenerated with Applicative.Applyer[Task, Task, Result, mill.util.Ctx] {

  implicit def apply[T](t: T)
                       (implicit rw: RW[T],
                        ctx: mill.define.Ctx): Target[T] = macro targetImpl[T]

  def targetImpl[T: c.WeakTypeTag](c: Context)
                                  (t: c.Expr[T])
                                  (rw: c.Expr[RW[T]],
                                   ctx: c.Expr[mill.define.Ctx]): c.Expr[Target[T]] = {
    import c.universe._
    val lhs = Applicative.impl0[Task, T, mill.util.Ctx](c)(reify(Result.Success(t.splice)).tree)

    mill.moduledefs.Cacher.impl0[TargetImpl[T]](c)(
      reify(
        new TargetImpl[T](lhs.splice, ctx.splice, rw.splice)
      )
    )
  }

  implicit def apply[T](t: Result[T])
                       (implicit rw: RW[T],
                        ctx: mill.define.Ctx): Target[T] = macro targetResultImpl[T]

  def targetResultImpl[T: c.WeakTypeTag](c: Context)
                                        (t: c.Expr[Result[T]])
                                        (rw: c.Expr[RW[T]],
                                         ctx: c.Expr[mill.define.Ctx]): c.Expr[Target[T]] = {
    import c.universe._
    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          Applicative.impl0[Task, T, mill.util.Ctx](c)(t.tree).splice,
          ctx.splice,
          rw.splice
        )
      )
    )
  }

  def apply[T](t: Task[T])
              (implicit rw: RW[T],
               ctx: mill.define.Ctx): Target[T] = macro targetTaskImpl[T]

  def targetTaskImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[Task[T]])
                                      (rw: c.Expr[RW[T]],
                                       ctx: c.Expr[mill.define.Ctx]): c.Expr[Target[T]] = {
    import c.universe._
    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](t.splice, ctx.splice, rw.splice)
      )
    )
  }

  def sources(values: Result[os.Path]*)
             (implicit ctx: mill.define.Ctx): Sources = macro sourcesImpl1

  def sourcesImpl1(c: Context)
                  (values: c.Expr[Result[os.Path]]*)
                  (ctx: c.Expr[mill.define.Ctx]): c.Expr[Sources] = {
    import c.universe._
    val wrapped =
      for (value <- values.toList)
      yield Applicative.impl0[Task, PathRef, mill.util.Ctx](c)(
        reify(value.splice.map(PathRef(_))).tree
      ).tree

    mill.moduledefs.Cacher.impl0[Sources](c)(
      reify(
        new Sources(
          Task.sequence(c.Expr[List[Task[PathRef]]](q"scala.List(..$wrapped)").splice),
          ctx.splice
        )
      )
    )
  }

  def sources(values: Result[Seq[PathRef]])
             (implicit ctx: mill.define.Ctx): Sources = macro sourcesImpl2

  def sourcesImpl2(c: Context)
                  (values: c.Expr[Result[Seq[PathRef]]])
                  (ctx: c.Expr[mill.define.Ctx]): c.Expr[Sources] = {
    import c.universe._


    mill.moduledefs.Cacher.impl0[Sources](c)(
      reify(
        new Sources(
          Applicative.impl0[Task, Seq[PathRef], mill.util.Ctx](c)(values.tree).splice,
          ctx.splice
        )
      )
    )
  }
  def input[T](value: Result[T])
              (implicit rw: RW[T],
                ctx: mill.define.Ctx): Input[T] = macro inputImpl[T]

  def inputImpl[T: c.WeakTypeTag](c: Context)
                                  (value: c.Expr[T])
                                  (rw: c.Expr[RW[T]],
                                   ctx: c.Expr[mill.define.Ctx]): c.Expr[Input[T]] = {
    import c.universe._

    mill.moduledefs.Cacher.impl0[Input[T]](c)(
      reify(
        new Input[T](
          Applicative.impl[Task, T, mill.util.Ctx](c)(value).splice,
          ctx.splice,
          rw.splice
        )
      )
    )
  }

  def command[T](t: Task[T])
                (implicit ctx: mill.define.Ctx,
                 w: W[T],
                 cls: EnclosingClass,
                 overrides: Overrides): Command[T] = {
    new Command(t, ctx, w, cls.value, overrides.value)
  }

  def command[T](t: Result[T])
                (implicit w: W[T],
                 ctx: mill.define.Ctx,
                 cls: EnclosingClass,
                 overrides: Overrides): Command[T] = macro commandImpl[T]

  def commandImpl[T: c.WeakTypeTag](c: Context)
                                   (t: c.Expr[T])
                                   (w: c.Expr[W[T]],
                                    ctx: c.Expr[mill.define.Ctx],
                                    cls: c.Expr[EnclosingClass],
                                    overrides: c.Expr[Overrides]): c.Expr[Command[T]] = {
    import c.universe._
    reify(
      new Command[T](
        Applicative.impl[Task, T, mill.util.Ctx](c)(t).splice,
        ctx.splice,
        w.splice,
        cls.splice.value,
        overrides.splice.value
      )
    )
  }

  def worker[T](t: Task[T])
               (implicit ctx: mill.define.Ctx): Worker[T] = new Worker(t, ctx)

  def worker[T](t: Result[T])
               (implicit ctx: mill.define.Ctx): Worker[T] = macro workerImpl[T]

  def workerImpl[T: c.WeakTypeTag](c: Context)
                                  (t: c.Expr[T])
                                  (ctx: c.Expr[mill.define.Ctx]): c.Expr[Worker[T]] = {
    import c.universe._
    reify(
      new Worker[T](Applicative.impl[Task, T, mill.util.Ctx](c)(t).splice, ctx.splice)
    )
  }

  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, mill.util.Ctx]

  def persistent[T](t: Result[T])(implicit rw: RW[T],
                                  ctx: mill.define.Ctx): Persistent[T] = macro persistentImpl[T]

  def persistentImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[T])
                                      (rw: c.Expr[RW[T]],
                                       ctx: c.Expr[mill.define.Ctx]): c.Expr[Persistent[T]] = {
    import c.universe._


    mill.moduledefs.Cacher.impl0[Persistent[T]](c)(
      reify(
        new Persistent[T](
          Applicative.impl[Task, T, mill.util.Ctx](c)(t).splice,
          ctx.splice,
          rw.splice
        )
      )
    )
  }

  type TT[+X] = Task[X]
  def makeT[X](inputs0: Seq[TT[_]], evaluate0: mill.util.Ctx => Result[X]) = new Task[X] {
    val inputs = inputs0
    def evaluate(x: mill.util.Ctx) = evaluate0(x)
  }

  def underlying[A](v: Task[A]) = v
  def mapCtx[A, B](t: Task[A])(f: (A, mill.util.Ctx) => Result[B]) = t.mapDest(f)
  def zip() =  new Task.Task0(())
  def zip[A](a: Task[A]) = a.map(Tuple1(_))
  def zip[A, B](a: Task[A], b: Task[B]) = a.zip(b)
}

abstract class NamedTaskImpl[+T](ctx0: mill.define.Ctx, t: Task[T]) extends NamedTask[T]{
  def evaluate(args: mill.util.Ctx) = args[T](0)
  val ctx = ctx0.copy(segments = ctx0.segments ++ Seq(ctx0.segment))
  val inputs = Seq(t)
}

class TargetImpl[+T](t: Task[T],
                     ctx0: mill.define.Ctx,
                     val readWrite: RW[_]) extends NamedTaskImpl[T](ctx0, t) with Target[T] {
}

class Command[+T](t: Task[T],
                  ctx0: mill.define.Ctx,
                  val writer: W[_],
                  val cls: Class[_],
                  val overrides: Int) extends NamedTaskImpl[T](ctx0, t) {
  override def asCommand = Some(this)
}

class Worker[+T](t: Task[T], ctx0: mill.define.Ctx) extends NamedTaskImpl[T](ctx0, t) {
  override def flushDest = false
  override def asWorker = Some(this)
}
class Persistent[+T](t: Task[T],
                     ctx0: mill.define.Ctx,
                     readWrite: RW[_])
  extends TargetImpl[T](t, ctx0, readWrite) {

  override def flushDest = false
}
class Input[T](t: Task[T],
               ctx0: mill.define.Ctx,
               val readWrite: RW[_]) extends NamedTaskImpl[T](ctx0, t) with Target[T]{
  override def sideHash = util.Random.nextInt()
}
class Sources(t: Task[Seq[PathRef]],
              ctx0: mill.define.Ctx) extends Input[Seq[PathRef]](
  t,
  ctx0,
  RW.join(
    upickle.default.SeqLikeReader[Seq, PathRef],
    upickle.default.SeqLikeWriter[Seq, PathRef]
  )
)
object Task {

  class Task0[T](t: T) extends Task[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: mill.util.Ctx)  = t0
  }

  abstract class Ops[+T]{ this: Task[T] =>
    def map[V](f: T => V) = new Task.Mapped(this, f)
    def mapDest[V](f: (T, mill.util.Ctx) => Result[V]) = new Task.MappedDest(this, f)

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
    def evaluate(args: mill.util.Ctx) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V]{
    def evaluate(args: mill.util.Ctx) = f(args(0))
    val inputs = List(source)
  }
  class MappedDest[+T, +V](source: Task[T], f: (T, mill.util.Ctx) => Result[V]) extends Task[V]{
    def evaluate(args: mill.util.Ctx) = f(args(0), args)
    val inputs = List(source)
  }
  class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)]{
    def evaluate(args: mill.util.Ctx) = (args(0), args(1))
    val inputs = List(source1, source2)
  }
}
