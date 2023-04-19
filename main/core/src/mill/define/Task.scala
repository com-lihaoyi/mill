package mill.define

import mill.api.{CompileProblemReporter, Logger, PathRef, Result, TestReporter}
import mill.define.Applicative.Applyable
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
abstract class Task[+T] extends Task.Ops[T] with Applyable[Task, T] {

  /**
   * What other tasks does this task depend on?
   */
  val inputs: Seq[Task[_]]

  /**
   * Evaluate this task
   */
  def evaluate(args: mill.api.Ctx): Result[T]

  /**
   * Even if this tasks's inputs did not change, does it need to re-evaluate
   * anyway?
   */
  def sideHash: Int = 0

  /**
   * Whether or not this [[Task]] deletes the `T.dest` folder between runs
   */
  def flushDest: Boolean = true

  def asTarget: Option[Target[T]] = None
  def asCommand: Option[Command[T]] = None
  def asWorker: Option[Worker[T]] = None
  def self: Task[T] = this
}

object Task {

  abstract class Ops[+T] { this: Task[T] =>
    def map[V](f: T => V) = new Task.Mapped(this, f)

    def filter(f: T => Boolean) = this
    def withFilter(f: T => Boolean) = this
    def zip[V](other: Task[V]) = new Task.Zipped(this, other)

  }

  class Sequence[+T](inputs0: Seq[Task[T]]) extends Task[Seq[T]] {
    val inputs = inputs0
    def evaluate(args: mill.api.Ctx) = {
      for (i <- 0 until args.length)
        yield args(i).asInstanceOf[T]
    }
  }
  class TraverseCtx[+T, V](
      inputs0: Seq[Task[T]],
      f: (IndexedSeq[T], mill.api.Ctx) => Result[V]
  ) extends Task[V] {
    val inputs = inputs0
    def evaluate(args: mill.api.Ctx) = {
      f(
        for (i <- 0 until args.length)
          yield args(i).asInstanceOf[T],
        args
      )
    }
  }
  class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V] {
    def evaluate(args: mill.api.Ctx) = f(args(0))
    val inputs = List(source)
  }
  class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)] {
    def evaluate(args: mill.api.Ctx) = (args(0), args(1))
    val inputs = List(source1, source2)
  }
}

/**
 * Represents a task that can be referenced by its path segments. `T{...}`
 * targets, `T.input`, `T.worker`, etc. but not including anonymous
 * `T.task` or `T.traverse` etc. instances
 */
trait NamedTask[+T] extends Task[T] {
  /**
   * The implementation task wrapped by this named task
   */
  def t: Task[T]
  def ctx0: mill.define.Ctx
  def isPrivate: Option[Boolean]
  def label: String = ctx.segment match {
    case Segment.Label(v) => v
    case Segment.Cross(_) => throw new IllegalArgumentException(
        "NamedTask only support a ctx with a Label segment, but found a Cross."
      )
  }
  override def toString = ctx.segments.render

  def evaluate(args: mill.api.Ctx) = args[T](0)

  val ctx = ctx0.withSegments(segments = ctx0.segments ++ Seq(ctx0.segment))
  val inputs = Seq(t)

  def readWriterOpt: Option[upickle.default.ReadWriter[_]] = None

  def writerOpt: Option[upickle.default.Writer[_]] = readWriterOpt.orElse(None)
}

/**
 * A Target is a [[NamedTask]] that is cached on disk; either a
 * [[TargetImpl]] or an [[InputImpl]]
 */
trait Target[+T] extends NamedTask[T]

object Target extends Applicative.Applyer[Task, Task, Result, mill.api.Ctx] {
  /**
   * Returns the implicit [[mill.api.Ctx.Dest]] in scope.
   */
  def dest(implicit ctx: mill.api.Ctx.Dest): os.Path = ctx.dest

  /**
   * Returns the implicit [[mill.api.Ctx.Log]] in scope.
   */
  def log(implicit ctx: mill.api.Ctx.Log): Logger = ctx.log

  /**
   * Returns the implicit [[mill.api.Ctx.Home]] in scope.
   */
  def home(implicit ctx: mill.api.Ctx.Home): os.Path = ctx.home

  /**
   * Returns the implicit [[mill.api.Ctx.Env]] in scope.
   */
  def env(implicit ctx: mill.api.Ctx.Env): Map[String, String] = ctx.env

  /**
   * Returns the implicit [[mill.api.Ctx.Args]] in scope.
   */
  def args(implicit ctx: mill.api.Ctx.Args): IndexedSeq[_] = ctx.args

  def testReporter(implicit ctx: mill.api.Ctx): TestReporter = ctx.testReporter
  def reporter(implicit ctx: mill.api.Ctx): Int => Option[CompileProblemReporter] = ctx.reporter
  def workspace(implicit ctx: mill.api.Ctx): os.Path = ctx.workspace

  implicit def apply[T](t: T)(implicit rw: RW[T], ctx: mill.define.Ctx): Target[T] =
    macro targetImpl[T]

  def targetImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(
      rw: c.Expr[RW[T]],
      ctx: c.Expr[mill.define.Ctx]
  ): c.Expr[Target[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    val lhs = Applicative.impl0[Task, T, mill.api.Ctx](c)(reify(Result.Success(t.splice)).tree)

    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          lhs.splice,
          ctx.splice,
          rw.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  private def isPrivateTargetOption(c: Context): c.Expr[Option[Boolean]] = {
    import c.universe._
    if (c.internal.enclosingOwner.isPrivate) reify(Some(true))
    else reify(Some(false))
  }

  implicit def apply[T](t: Result[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Target[T] =
    macro targetResultImpl[T]

  def targetResultImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Result[T]])(
      rw: c.Expr[RW[T]],
      ctx: c.Expr[mill.define.Ctx]
  ): c.Expr[Target[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          Applicative.impl0[Task, T, mill.api.Ctx](c)(t.tree).splice,
          ctx.splice,
          rw.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def apply[T](t: Task[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Target[T] =
    macro targetTaskImpl[T]

  def targetTaskImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Task[T]])(
      rw: c.Expr[RW[T]],
      ctx: c.Expr[mill.define.Ctx]
  ): c.Expr[Target[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[Target[T]](c)(
      reify(
        new TargetImpl[T](
          t.splice,
          ctx.splice,
          rw.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def sources(values: Result[os.Path]*)(implicit ctx: mill.define.Ctx): Target[Seq[PathRef]] =
    macro sourcesImpl1

  def sourcesImpl1(c: Context)(values: c.Expr[Result[os.Path]]*)(ctx: c.Expr[mill.define.Ctx])
      : c.Expr[Target[Seq[PathRef]]] = {
    import c.universe._
    val wrapped =
      for (value <- values.toList)
        yield Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(
          reify(value.splice.map(PathRef(_))).tree
        ).tree

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[SourcesImpl](c)(
      reify(
        new SourcesImpl(
          Target.sequence(c.Expr[List[Task[PathRef]]](q"_root_.scala.List(..$wrapped)").splice),
          ctx.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def sources(values: Result[Seq[PathRef]])(implicit ctx: mill.define.Ctx): Target[Seq[PathRef]] =
    macro sourcesImpl2

  def sourcesImpl2(c: Context)(values: c.Expr[Result[Seq[PathRef]]])(ctx: c.Expr[mill.define.Ctx])
      : c.Expr[Target[Seq[PathRef]]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[SourcesImpl](c)(
      reify(
        new SourcesImpl(
          Applicative.impl0[Task, Seq[PathRef], mill.api.Ctx](c)(values.tree).splice,
          ctx.splice,
          taskIsPrivate.splice
        )
      )
    )
  }
  def source(value: Result[os.Path])(implicit ctx: mill.define.Ctx): Target[PathRef] =
    macro sourceImpl1

  def sourceImpl1(c: Context)(value: c.Expr[Result[os.Path]])(ctx: c.Expr[mill.define.Ctx])
      : c.Expr[Target[PathRef]] = {
    import c.universe._

    val wrapped =
      Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(
        reify(value.splice.map(PathRef(_))).tree
      )

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[Target[PathRef]](c)(
      reify(
        new SourceImpl(
          wrapped.splice,
          ctx.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def source(value: Result[PathRef])(implicit ctx: mill.define.Ctx): Target[PathRef] =
    macro sourceImpl2

  def sourceImpl2(c: Context)(value: c.Expr[Result[PathRef]])(ctx: c.Expr[mill.define.Ctx])
      : c.Expr[Target[PathRef]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[Target[PathRef]](c)(
      reify(
        new SourceImpl(
          Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(value.tree).splice,
          ctx.splice,
          taskIsPrivate.splice
        )
      )
    )
  }
  def input[T](value: Result[T])(implicit
      w: upickle.default.Writer[T],
      ctx: mill.define.Ctx
  ): Target[T] =
    macro inputImpl[T]

  def inputImpl[T: c.WeakTypeTag](c: Context)(value: c.Expr[T])(
      w: c.Expr[upickle.default.Writer[T]],
      ctx: c.Expr[mill.define.Ctx]
  ): c.Expr[Target[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[InputImpl[T]](c)(
      reify(
        new InputImpl[T](
          Applicative.impl[Task, T, mill.api.Ctx](c)(value).splice,
          ctx.splice,
          w.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def command[T](t: Task[T])(implicit
      ctx: mill.define.Ctx,
      w: W[T],
      cls: EnclosingClass
  ): Command[T] = macro commandFromTask[T]

  def commandFromTask[T: c.WeakTypeTag](c: Context)(t: c.Expr[Task[T]])(
      ctx: c.Expr[mill.define.Ctx],
      w: c.Expr[W[T]],
      cls: c.Expr[EnclosingClass]
  ): c.Expr[Command[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    reify(
      new Command[T](
        t.splice,
        ctx.splice,
        w.splice,
        cls.splice.value,
        taskIsPrivate.splice
      )
    )
  }

  def command[T](t: Result[T])(implicit
      w: W[T],
      ctx: mill.define.Ctx,
      cls: EnclosingClass
  ): Command[T] = macro commandImpl[T]

  def commandImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(
      w: c.Expr[W[T]],
      ctx: c.Expr[mill.define.Ctx],
      cls: c.Expr[EnclosingClass]
  ): c.Expr[Command[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    reify(
      new Command[T](
        Applicative.impl[Task, T, mill.api.Ctx](c)(t).splice,
        ctx.splice,
        w.splice,
        cls.splice.value,
        taskIsPrivate.splice
      )
    )
  }

  def worker[T](t: Task[T])(implicit ctx: mill.define.Ctx): Worker[T] = macro workerImpl1[T]

  def workerImpl1[T: c.WeakTypeTag](c: Context)(t: c.Expr[Task[T]])(ctx: c.Expr[mill.define.Ctx])
      : c.Expr[Worker[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[Worker[T]](c)(
      reify(
        new Worker[T](t.splice, ctx.splice, taskIsPrivate.splice)
      )
    )
  }
  def worker[T](t: Result[T])(implicit ctx: mill.define.Ctx): Worker[T] = macro workerImpl2[T]

  def workerImpl2[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(ctx: c.Expr[mill.define.Ctx])
      : c.Expr[Worker[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[Worker[T]](c)(
      reify(
        new Worker[T](
          Applicative.impl[Task, T, mill.api.Ctx](c)(t).splice,
          ctx.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, mill.api.Ctx]

  def persistent[T](t: Result[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Target[T] =
    macro persistentImpl[T]

  def persistentImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(
      rw: c.Expr[RW[T]],
      ctx: c.Expr[mill.define.Ctx]
  ): c.Expr[PersistentImpl[T]] = {
    import c.universe._

    val taskIsPrivate = isPrivateTargetOption(c)

    mill.moduledefs.Cacher.impl0[PersistentImpl[T]](c)(
      reify(
        new PersistentImpl[T](
          Applicative.impl[Task, T, mill.api.Ctx](c)(t).splice,
          ctx.splice,
          rw.splice,
          taskIsPrivate.splice
        )
      )
    )
  }

  def traverse[T, V](source: Seq[T])(f: T => Task[V]) = {
    new Task.Sequence[V](source.map(f))
  }
  def sequence[T](source: Seq[Task[T]]) = new Task.Sequence[T](source)
  def traverseCtx[I, R](xs: Seq[Task[I]])(f: (IndexedSeq[I], mill.api.Ctx) => Result[R])
      : Task[R] = {
    new Task.TraverseCtx[I, R](xs, f)
  }
}

/**
 * A [[TargetImpl]] is the most common [[Task]] a user would encounter, commonly
 * defined using the `def foo = T{...}` syntax. [[TargetImpl]]s require that their
 * return type is JSON serializable. In return they automatically caches their
 * return value to disk, only re-computing if upstream [[Task]]s change
 *
 * @param readWriter the JSON serializer used for this [[TargetImpl]]'s return
 *                   type
 */
class TargetImpl[+T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val readWriter: RW[_],
    val isPrivate: Option[Boolean]
) extends Target[T] {
  override def asTarget: Option[Target[T]] = Some(this)
  override def readWriterOpt = Some(readWriter)
}

/**
 * [[PersistentImpl]] are a flavor of [[TargetImpl]], normally defined using
 * the `T.persistent{...}` syntax. The main difference is that while
 * [[TargetImpl]] deletes the `T.dest` folder in between runs,
 * [[PersistentImpl]] preserves it. This lets the user make use of files on
 * disk that persistent between runs of the task, e.g. to implement their own
 * fine-grained caching beyond what Mill provides by default.
 *
 * Note that the user defining a `T.persistent` task is taking on the
 * responsibility of ensuring that their implementation is idempotent, i.e.
 * that it computes the same result whether or not there is data in `T.dest`.
 * Violating that invariant can result in confusing mis-behaviors
 */
class PersistentImpl[+T](
    t: Task[T],
    ctx0: mill.define.Ctx,
    readWriter: RW[_],
    isPrivate: Option[Boolean]
) extends TargetImpl[T](t, ctx0, readWriter, isPrivate) {
  override def flushDest = false
}

/**
 * [[Command]]s are only [[NamedTask]]s defined using
 * `def foo() = T.command{...}` and are typically called from the
 * command-line. Unlike other [[NamedTask]]s, [[Command]]s can be defined to
 * take arguments that are automatically converted to command-line
 * arguments, as long as an implicit [[mainargs.TokensReader]] is available.
 */
class Command[+T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val writer: W[_],
    val cls: Class[_],
    val isPrivate: Option[Boolean]
) extends NamedTask[T] {
  override def asCommand = Some(this)
  override def writerOpt = Some(writer)
}

/**
 * [[Worker]] is a [[NamedTask]] that lives entirely in-memory, defined using
 * `T.worker{...}`. The value returned by `T.worker{...}` is long-lived,
 * persisting as long as the Mill process is kept alive (e.g. via `--watch`,
 * or via its default `MillServerMain` server process). This allows the user to
 * perform in-memory caching that is even more aggressive than the disk-based
 * caching enabled by [[PersistentImpl]]: your [[Worker]] can cache running
 * sub-processes, JVM Classloaders with JITed code, and all sorts of things
 * that do not easily serialize to JSON on disk.
 *
 * Like [[PersistentImpl]], The user defining a [[Worker]] assumes the
 * responsibility of ensuring the implementation is idempotent regardless of
 * what in-memory state the worker may have.
 */
class Worker[+T](val t: Task[T], val ctx0: mill.define.Ctx, val isPrivate: Option[Boolean])
    extends NamedTask[T] {
  override def flushDest = false
  override def asWorker = Some(this)
}

/**
 * [[InputImpl]]s, normally defined using `T.input`, are [[NamedTask]]s that
 * re-evaluate every time Mill is run. This is in contrast to [[TargetImpl]]s
 * which only re-evaluate when upstream tasks change.
 *
 * [[InputImpl]]s are useful when you want to capture some input to the Mill
 * build graph that comes from outside: maybe from an environment variable, a
 * JVM system property, the hash returned by `git rev-parse HEAD`. Reading
 * these external mutable variables inside a `T{...}` [[TargetImpl]] will
 * incorrectly cache them forever. Reading them inside a `T.input{...}`
 * will re-compute them every time, and only if the value changes would it
 * continue to invalidate downstream [[TargetImpl]]s
 *
 * The most common case of [[InputImpl]] is [[SourceImpl]] and [[SourcesImpl]],
 * used for detecting changes to source files.
 */
class InputImpl[T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val writer: upickle.default.Writer[_],
    val isPrivate: Option[Boolean]
) extends Target[T] {
  override def sideHash = util.Random.nextInt()
  override def writerOpt = Some(writer)
}

/**
 * A specialization of [[InputImpl]] defined via `T.sources`, [[SourcesImpl]]
 * uses [[PathRef]]s to compute a signature for a set of source files and
 * folders.
 *
 * This is most used when detecting changes in source code: when you edit a
 * file and run `mill compile`, it is the `T.sources` that re-computes the
 * signature for you source files/folders and decides whether or not downstream
 * [[TargetImpl]]s need to be invalidated and re-computed.
 */
class SourcesImpl(t: Task[Seq[PathRef]], ctx0: mill.define.Ctx, isPrivate: Option[Boolean])
    extends InputImpl[Seq[PathRef]](
      t,
      ctx0,
      upickle.default.readwriter[Seq[PathRef]],
      isPrivate
    ) {
  override def readWriterOpt = Some(upickle.default.readwriter[Seq[PathRef]])
}

/**
 * Similar to [[Source]], but only for a single source file or folder. Defined
 * using `T.source`.
 */
class SourceImpl(t: Task[PathRef], ctx0: mill.define.Ctx, isPrivate: Option[Boolean])
    extends InputImpl[PathRef](
      t,
      ctx0,
      upickle.default.readwriter[PathRef],
      isPrivate
    ) {
  override def readWriterOpt = Some(upickle.default.readwriter[PathRef])
}
