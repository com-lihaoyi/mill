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
  def lineNum: Int
  def readWrite: RW[_]
}

object Target extends TargetGenerated with Applicative.Applyer[Task, Task, Result, Ctx] {

  implicit def apply[T](t: T)
                       (implicit r: R[T],
                        w: W[T],
                        e: sourcecode.Enclosing,
                        l: sourcecode.Line,
                        n: sourcecode.Name,
                        cl: Caller[mill.define.Task.Module],
                        o: Overrides): Target[T] = macro targetImpl[T]

  def targetImpl[T: c.WeakTypeTag](c: Context)
                                  (t: c.Expr[T])
                                  (r: c.Expr[R[T]],
                                   w: c.Expr[W[T]],
                                   e: c.Expr[sourcecode.Enclosing],
                                   l: c.Expr[sourcecode.Line],
                                   n: c.Expr[sourcecode.Name],
                                   cl: c.Expr[Caller[mill.define.Task.Module]],
                                   o: c.Expr[Overrides]): c.Expr[Target[T]] = {
    import c.universe._
    val lhs = Applicative.impl0[Task, T, Ctx](c)(reify(Result.Success(t.splice)).tree)

    mill.moduledefs.Cacher.impl0[TargetImpl[T]](c)(
      reify(
        new TargetImpl[T](
          lhs.splice,
          e.splice.value,
          l.splice.value,
          cl.splice.value,
          n.splice.value,
          upickle.default.ReadWriter(w.splice.write, r.splice.read), o.splice.value
        )
      )
    )
  }

  implicit def apply[T](t: Result[T])
                       (implicit r: R[T],
                        w: W[T],
                        e: sourcecode.Enclosing,
                        l: sourcecode.Line,
                        n: sourcecode.Name,
                        cl: Caller[mill.define.Task.Module],
                        o: Overrides): Target[T] = macro targetResultImpl[T]

  def targetResultImpl[T: c.WeakTypeTag](c: Context)
                                        (t: c.Expr[Result[T]])
                                        (r: c.Expr[R[T]],
                                         w: c.Expr[W[T]],
                                         e: c.Expr[sourcecode.Enclosing],
                                         l: c.Expr[sourcecode.Line],
                                         n: c.Expr[sourcecode.Name],
                                         cl: c.Expr[Caller[mill.define.Task.Module]],
                                         o: c.Expr[Overrides]): c.Expr[Target[T]] = {
    import c.universe._
    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          Applicative.impl0[Task, T, Ctx](c)(t.tree).splice,
          e.splice.value,
          l.splice.value,
          cl.splice.value,
          n.splice.value,
          upickle.default.ReadWriter(w.splice.write, r.splice.read),
          o.splice.value
        )
      )
    )
  }

  def apply[T](t: Task[T])
              (implicit r: R[T],
               w: W[T],
               e: sourcecode.Enclosing,
               l: sourcecode.Line,
               n: sourcecode.Name,
               cl: Caller[mill.define.Task.Module],
               o: Overrides): Target[T] = macro targetTaskImpl[T]

  def targetTaskImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[Task[T]])
                                      (r: c.Expr[R[T]],
                                       w: c.Expr[W[T]],
                                       e: c.Expr[sourcecode.Enclosing],
                                       l: c.Expr[sourcecode.Line],
                                       n: c.Expr[sourcecode.Name],
                                       cl: c.Expr[Caller[mill.define.Task.Module]],
                                       o: c.Expr[Overrides]): c.Expr[Target[T]] = {
    import c.universe._
    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          t.splice,
          e.splice.value,
          l.splice.value,
          cl.splice.value,
          n.splice.value,
          upickle.default.ReadWriter(w.splice.write, r.splice.read),
          o.splice.value
        )
      )
    )
  }

  def command[T](t: Result[T])
                (implicit w: W[T],
                 e: sourcecode.Enclosing,
                 n: sourcecode.Name,
                 cl: Caller[mill.define.Task.Module],
                 o: Overrides): Command[T] = macro commandImpl[T]

  def source(value: Result[ammonite.ops.Path])
            (implicit r: R[PathRef],
             w: W[PathRef],
             e: sourcecode.Enclosing,
             l: sourcecode.Line,
             n: sourcecode.Name,
             cl: Caller[mill.define.Task.Module],
             o: Overrides): Input[PathRef] = macro sourceImpl

  def sourceImpl(c: Context)
                (value: c.Expr[Result[ammonite.ops.Path]])
                (r: c.Expr[R[PathRef]],
                 w: c.Expr[W[PathRef]],
                 e: c.Expr[sourcecode.Enclosing],
                 l: c.Expr[sourcecode.Line],
                 n: c.Expr[sourcecode.Name],
                 cl: c.Expr[Caller[mill.define.Task.Module]],
                 o: c.Expr[Overrides]): c.Expr[Input[PathRef]] = {
    import c.universe._
    val wrapped: c.Expr[Result[PathRef]] = reify(value.splice match{
      case Result.Success(p) => Result.Success(PathRef(p))
      case x: Result.Failing => x
    })
    mill.moduledefs.Cacher.impl0[Input[PathRef]](c)(
      reify(
        new Input[PathRef](
          Applicative.impl0[Task, PathRef, Ctx](c)(wrapped.tree).splice,
          e.splice.value,
          l.splice.value,
          cl.splice.value,
          n.splice.value,
          upickle.default.ReadWriter(w.splice.write, r.splice.read),
          o.splice.value
        )
      )
    )
  }

  def input[T](value: Result[T])
              (implicit r: R[T],
                w: W[T],
                e: sourcecode.Enclosing,
                l: sourcecode.Line,
                n: sourcecode.Name,
                cl: Caller[mill.define.Task.Module],
                o: Overrides): Input[T] = macro inputImpl[T]

  def inputImpl[T: c.WeakTypeTag](c: Context)
                                  (value: c.Expr[T])
                                  (r: c.Expr[R[T]],
                                   w: c.Expr[W[T]],
                                   e: c.Expr[sourcecode.Enclosing],
                                   l: c.Expr[sourcecode.Line],
                                   n: c.Expr[sourcecode.Name],
                                   cl: c.Expr[Caller[mill.define.Task.Module]],
                                   o: c.Expr[Overrides]): c.Expr[Input[T]] = {
    import c.universe._

    mill.moduledefs.Cacher.impl0[Input[T]](c)(
      reify(
        new Input[T](
          Applicative.impl[Task, T, Ctx](c)(value).splice,
          e.splice.value,
          l.splice.value,
          cl.splice.value,
          n.splice.value,
          upickle.default.ReadWriter(w.splice.write, r.splice.read),
          o.splice.value
        )
      )
    )
  }

  def command[T](t: Task[T])
                (implicit c: Caller[Task.Module],
                 e: sourcecode.Enclosing,
                 n: sourcecode.Name,
                 w: W[T],
                 o: Overrides): Command[T] = new Command(t, e.value, c.value, n.value, w, o.value)

  def commandImpl[T: c.WeakTypeTag](c: Context)
                                   (t: c.Expr[T])
                                   (w: c.Expr[W[T]],
                                    e: c.Expr[sourcecode.Enclosing],
                                    n: c.Expr[sourcecode.Name],
                                    cl: c.Expr[Caller[mill.define.Task.Module]],
                                    o: c.Expr[Overrides]): c.Expr[Command[T]] = {
    import c.universe._
    reify(
      new Command[T](
        Applicative.impl[Task, T, Ctx](c)(t).splice,
        e.splice.value,
        cl.splice.value,
        n.splice.value,
        w.splice,
        o.splice.value
      )
    )
  }

  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, Ctx]

  def persistent[T](t: Result[T])(implicit r: R[T],
                                  w: W[T],
                                  e: sourcecode.Enclosing,
                                  l: sourcecode.Line,
                                  n: sourcecode.Name,
                                  cl: Caller[mill.define.Task.Module],
                                  o: Overrides): Target[T] = macro persistentImpl[T]

  def persistentImpl[T: c.WeakTypeTag](c: Context)
                                      (t: c.Expr[T])
                                      (r: c.Expr[R[T]],
                                       w: c.Expr[W[T]],
                                       e: c.Expr[sourcecode.Enclosing],
                                       l: c.Expr[sourcecode.Line],
                                       n: c.Expr[sourcecode.Name],
                                       cl: c.Expr[Caller[mill.define.Task.Module]],
                                       o: c.Expr[Overrides]): c.Expr[Persistent[T]] = {
    import c.universe._


    mill.moduledefs.Cacher.impl0[Persistent[T]](c)(
      reify(
        new Persistent[T](
          Applicative.impl[Task, T, Ctx](c)(t).splice,
          e.splice.value,
          l.splice.value,
          cl.splice.value,
          n.splice.value,
          upickle.default.ReadWriter(w.splice.write, r.splice.read),
          o.splice.value
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
                     val enclosing: String,
                     val lineNum: Int,
                     val owner: Task.Module,
                     val name: String,
                     val readWrite: RW[_],
                     val overrides: Int) extends Target[T] {
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def toString = enclosing + "@" + Integer.toHexString(System.identityHashCode(this))
}
class Command[+T](t: Task[T],
                  val enclosing: String,
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
                     lineNum: Int,
                     owner: Task.Module,
                     name: String,
                     readWrite: RW[_],
                     overrides: Int)
  extends TargetImpl[T](t, enclosing, lineNum, owner, name, readWrite, overrides) {
  override def flushDest = false
  override def asPersistent = Some(this)
}
class Input[T](t: Task[T],
               val enclosing: String,
               val lineNum: Int,
               val owner: Task.Module,
               val name: String,
               val readWrite: RW[_],
               val overrides: Int) extends Target[T]{
  val inputs = Seq(t)
  def evaluate(args: Ctx) = args[T](0)
  override def sideHash = util.Random.nextInt()
}

object Task {


  trait TaskModule extends Module {
    def defaultCommandName(): String
  }

  /**
    * `Module` is a class meant to be extended by `trait`s *only*, in order to
    * propagate the implicit parameters forward to the final concrete
    * instantiation site so they can capture the enclosing/line information of
    * the concrete instance.
    */
  class Module(implicit millModuleEnclosing0: sourcecode.Enclosing,
               millModuleLine0: sourcecode.Line) extends mill.moduledefs.Cacher{
    // Ensure we do not propagate the implicit parameters as implicits within
    // the body of any inheriting class/trait/objects, as it would screw up any
    // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
    val millModuleEnclosing = millModuleEnclosing0
    val millModuleLine = millModuleLine0
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
