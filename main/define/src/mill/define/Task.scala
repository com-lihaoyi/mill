package mill.define

import mill.api.{CompileProblemReporter, Logger, PathRef, Result, TestReporter}
import mill.define.Applicative.Applyable
import upickle.default.{ReadWriter => RW, Writer => W}

import scala.annotation.nowarn
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

/**
 * Models a single node in the Mill build graph, with a list of inputs and a
 * single output of type [[T]].
 *
 * Generally not instantiated manually, but instead constructed via the
 * [[Task.apply]] & similar macros.
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
   * Even if this task's inputs did not change, does it need to re-evaluate
   * anyway?
   */
  def sideHash: Int = 0

  /**
   * Whether this [[Task]] deletes the `Task.dest` folder between runs
   */
  def flushDest: Boolean = true

  /**
   * A list of paths under the `Task.dest` folder that should be considered root source directories
   * when generating configuration for IDEs.
   */
  def deferredGeneratedSourceRoots: Seq[os.SubPath] = Seq.empty

  def asTarget: Option[Task.Simple[T]] = None
  def asCommand: Option[Command[T]] = None
  def asWorker: Option[Worker[T]] = None
  def self: Task[T] = this
  def isExclusiveCommand: Boolean = this match {
    case c: Command[_] if c.exclusive => true
    case _ => false
  }
}

object Task extends TaskBase {

  // Forward compatibility with Mill 1.0
  @nowarn("cat=deprecation")
  type Simple[+T] = Target[T]

  /**
   * A specialization of [[InputImpl]] defined via `Task.Sources`, [[SourcesImpl]]
   * uses [[PathRef]]s to compute a signature for a set of source files and
   * folders.
   *
   * This is most used when detecting changes in source code: when you edit a
   * file and run `mill compile`, it is the `Task.Sources` that re-computes the
   * signature for you source files/folders and decides whether downstream
   * [[TargetImpl]]s need to be invalidated and re-computed.
   */
  def Sources(values: Result[os.Path]*)(implicit ctx: mill.define.Ctx): Task.Simple[Seq[PathRef]] =
    macro Target.Internal.sourcesImpl1

  def Sources(values: Result[Seq[PathRef]])(implicit
      ctx: mill.define.Ctx
  ): Task.Simple[Seq[PathRef]] =
    macro Target.Internal.sourcesImpl2

  def Sources(values: os.SubPath*)(implicit ctx: mill.define.Ctx): Task.Simple[Seq[PathRef]] =
    macro Target.Internal.sourcesImpl3

  /**
   * Similar to [[Source]], but only for a single source file or folder. Defined
   * using `Task.Source`.
   */
  def Source(value: Result[os.Path])(implicit ctx: mill.define.Ctx): Task.Simple[PathRef] =
    macro Target.Internal.sourceImpl1

  def Source(value: Result[PathRef])(implicit ctx: mill.define.Ctx): Task.Simple[PathRef] =
    macro Target.Internal.sourceImpl2

  def Source(value: os.SubPath)(implicit ctx: mill.define.Ctx): Task.Simple[PathRef] =
    macro Target.Internal.sourceImpl3

  /**
   * [[InputImpl]]s, normally defined using `Task.Input`, are [[NamedTask]]s that
   * re-evaluate every time Mill is run. This is in contrast to [[TargetImpl]]s
   * which only re-evaluate when upstream tasks change.
   *
   * [[InputImpl]]s are useful when you want to capture some input to the Mill
   * build graph that comes from outside: maybe from an environment variable, a
   * JVM system property, the hash returned by `git rev-parse HEAD`. Reading
   * these external mutable variables inside a `Task{...}` [[TargetImpl]] will
   * incorrectly cache them forever. Reading them inside a `Task.Input{...}`
   * will re-compute them every time, and only if the value changes would it
   * continue to invalidate downstream [[TargetImpl]]s
   *
   * The most common case of [[InputImpl]] is [[SourceImpl]] and [[SourcesImpl]],
   * used for detecting changes to source files.
   */
  def Input[T](value: Result[T])(implicit
      w: upickle.default.Writer[T],
      ctx: mill.define.Ctx
  ): Task.Simple[T] =
    macro Target.Internal.inputImpl[T]

  /**
   * [[Command]]s are only [[NamedTask]]s defined using
   * `def foo() = Task.Command{...}` and are typically called from the
   * command-line. Unlike other [[NamedTask]]s, [[Command]]s can be defined to
   * take arguments that are automatically converted to command-line
   * arguments, as long as an implicit [[mainargs.TokensReader]] is available.
   */
  def Command[T](t: Result[T])(implicit
      w: W[T],
      ctx: mill.define.Ctx,
      cls: EnclosingClass
  ): Command[T] = macro Target.Internal.commandImpl[T]

  /** Binary compatibility forwarder. */
  def Command(
      t: NamedParameterOnlyDummy,
      exclusive: Boolean
  ): CommandFactory = new CommandFactory(exclusive = exclusive, persistent = false)

  /**
   * @param exclusive Exclusive commands run serially at the end of an evaluation,
   *                  without any other tasks running parallel, and without the
   *                  terminal logging prefixes that are applied to normal tasks.
   *                  These are normally used for "top level" commands which are
   *                  run directly to perform some action or display some output
   *                  to the user.
   * @param persistent Persistent comands do not erase the `Task.dest` folder
   *                   between runs.
   */
  def Command(
      t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      exclusive: Boolean = false,
      persistent: Boolean = false
  ): CommandFactory = new CommandFactory(exclusive = exclusive, persistent = persistent)
  class CommandFactory private[mill] (val exclusive: Boolean, val persistent: Boolean)
      extends TaskBase.TraverseCtxHolder {

    /** Binary compatibility forwarder */
    private[mill] def this(exclusive: Boolean) = this(exclusive, persistent = false)

    def apply[T](t: Result[T])(implicit
        w: W[T],
        ctx: mill.define.Ctx,
        cls: EnclosingClass
    ): Command[T] = macro Target.Internal.serialCommandImpl[T]
  }

  /**
   * [[Worker]] is a [[NamedTask]] that lives entirely in-memory, defined using
   * `Task.Worker{...}`. The value returned by `Task.Worker{...}` is long-lived,
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
  def Worker[T](t: Result[T])(implicit ctx: mill.define.Ctx): Worker[T] =
    macro Target.Internal.workerImpl2[T]

  /**
   * Creates an anonymous `Task`. These depend on other tasks and
   * be-depended-upon by other tasks, but cannot be run directly from the
   * command line and do not perform any caching. Typically used as helpers to
   * implement `Task{...}` targets.
   */
  def Anon[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, mill.api.Ctx]

  @deprecated(
    "Creating a target from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  def apply[T](t: Task[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Target.Internal.targetTaskImpl[T]

  def apply[T](t: T)(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Target.Internal.targetImpl[T]

  def apply[T](t: Result[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Target.Internal.targetResultImpl[T]

  /**
   * Persistent tasks are defined using
   * the `Task(persistent = true){...}` syntax. The main difference is that while
   * [[TargetImpl]] deletes the `Task.dest` folder in between runs,
   * [[PersistentImpl]] preserves it. This lets the user make use of files on
   * disk that persistent between runs of the task, e.g. to implement their own
   * fine-grained caching beyond what Mill provides by default.
   *
   * Note that the user defining a `Task(persistent = true)` task is taking on the
   * responsibility of ensuring that their implementation is idempotent, i.e.
   * that it computes the same result whether there is data in `Task.dest`.
   * Violating that invariant can result in confusing mis-behaviors
   */
  def apply(
      t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      persistent: Boolean = false,
      deferredGeneratedSourceRoots: Seq[os.SubPath] = Seq.empty
  ): ApplyFactory = new ApplyFactory(persistent, deferredGeneratedSourceRoots)

  // Kept for bincompat reasons
  protected def apply(
      t: NamedParameterOnlyDummy,
      persistent: Boolean
  ): ApplyFactory = new ApplyFactory(persistent, Seq.empty)

  class ApplyFactory private[mill] (
      val persistent: Boolean,
      val deferredGeneratedSourceRoots: Seq[os.SubPath]
  ) extends TaskBase.TraverseCtxHolder {
    // Kept for bincompat reasons
    protected def this(persistent: Boolean) = this(persistent, Seq.empty)

    def apply[T](t: Result[T])(implicit
        rw: RW[T],
        ctx: mill.define.Ctx
    ): Task.Simple[T] = macro Target.Internal.persistentTargetResultImpl[T]
  }

  abstract class Ops[+T] { this: Task[T] =>
    def map[V](f: T => V): Task[V] = new Task.Mapped(this, f)
    def filter(f: T => Boolean): Task[T] = this
    def withFilter(f: T => Boolean): Task[T] = this
    def zip[V](other: Task[V]): Task[(T, V)] = new Task.Zipped(this, other)

  }

  private[define] class Sequence[+T](inputs0: Seq[Task[T]]) extends Task[Seq[T]] {
    val inputs: Seq[Task[_]] = inputs0
    def evaluate(ctx: mill.api.Ctx): Result[Seq[T]] = {
      for (i <- 0 until ctx.args.length)
        yield ctx.args(i).asInstanceOf[T]
    }
  }
  private[define] class TraverseCtx[+T, V](
      inputs0: Seq[Task[T]],
      f: (IndexedSeq[T], mill.api.Ctx) => Result[V]
  ) extends Task[V] {
    val inputs: Seq[Task[_]] = inputs0
    def evaluate(ctx: mill.api.Ctx): Result[V] = {
      f(
        for (i <- 0 until ctx.args.length)
          yield ctx.args(i).asInstanceOf[T],
        ctx
      )
    }
  }

  private[define] class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V] {
    def evaluate(ctx: mill.api.Ctx): Result[V] = f(ctx.arg(0))
    val inputs: Seq[Task[_]] = List(source)
  }

  private[define] class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)] {
    def evaluate(ctx: mill.api.Ctx): Result[(T, V)] = (ctx.arg(0), ctx.arg(1))
    val inputs: Seq[Task[_]] = List(source1, source2)
  }
}

/**
 * Represents a task that can be referenced by its path segments. `Task{...}`
 * targets, `Task.Input`, `Task.Worker`, etc. but not including anonymous
 * `Task.Anon` or `Task.traverse` etc. instances
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

  def evaluate(ctx: mill.api.Ctx): Result[T] = ctx.arg[T](0)

  val ctx: Ctx = ctx0.withSegments(segments = ctx0.segments ++ Seq(ctx0.segment))
  val inputs: Seq[Task[_]] = Seq(t)

  def readWriterOpt: Option[upickle.default.ReadWriter[_]] = None

  def writerOpt: Option[upickle.default.Writer[_]] = readWriterOpt.orElse(None)
}

/**
 * A Target is a [[NamedTask]] that is cached on disk; either a
 * [[TargetImpl]] or an [[InputImpl]]
 */
@deprecated("Use Task.Simple[T] or T[T] instead", "Mill 0.12.15")
trait Target[+T] extends NamedTask[T]

object Target extends TaskBase {
  @deprecated("Use Task(persistent = true){...} instead", "Mill after 0.12.0-RC1")
  def persistent[T](t: Result[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Target.Internal.persistentImpl[T]

  @deprecated("Use Task.Sources instead", "Mill after 0.12.0-RC1")
  def sources(values: Result[os.Path]*)(implicit ctx: mill.define.Ctx): Task.Simple[Seq[PathRef]] =
    macro Target.Internal.sourcesImpl1
  @deprecated("Use Task.Sources instead", "Mill after 0.12.0-RC1")
  def sources(values: Result[Seq[PathRef]])(implicit
      ctx: mill.define.Ctx
  ): Task.Simple[Seq[PathRef]] =
    macro Target.Internal.sourcesImpl2

  @deprecated("Use Task.Source instead", "Mill after 0.12.0-RC1")
  def source(value: Result[os.Path])(implicit ctx: mill.define.Ctx): Task.Simple[PathRef] =
    macro Target.Internal.sourceImpl1

  @deprecated("Use Task.Source instead", "Mill after 0.12.0-RC1")
  def source(value: Result[PathRef])(implicit ctx: mill.define.Ctx): Task.Simple[PathRef] =
    macro Target.Internal.sourceImpl2

  @deprecated("Use Task.Input instead", "Mill after 0.12.0-RC1")
  def input[T](value: Result[T])(implicit
      w: upickle.default.Writer[T],
      ctx: mill.define.Ctx
  ): Task.Simple[T] =
    macro Target.Internal.inputImpl[T]

  @deprecated(
    "Creating a command from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  def command[T](t: Task[T])(implicit
      ctx: mill.define.Ctx,
      w: W[T],
      cls: EnclosingClass
  ): Command[T] = macro Target.Internal.commandFromTask[T]

  @deprecated("Use Task.Command instead", "Mill after 0.12.0-RC1")
  def command[T](t: Result[T])(implicit
      w: W[T],
      ctx: mill.define.Ctx,
      cls: EnclosingClass
  ): Command[T] = macro Target.Internal.commandImpl[T]

  @deprecated(
    "Creating a worker from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  def worker[T](t: Task[T])(implicit ctx: mill.define.Ctx): Worker[T] =
    macro Target.Internal.workerImpl1[T]

  @deprecated("Use Task.Worker instead", "Mill after 0.12.0-RC1")
  def worker[T](t: Result[T])(implicit ctx: mill.define.Ctx): Worker[T] =
    macro Target.Internal.workerImpl2[T]

  @deprecated("Use Task.Anon instead", "Mill after 0.12.0-RC2")
  def task[T](t: Result[T]): Task[T] = macro Applicative.impl[Task, T, mill.api.Ctx]

  @deprecated(
    "Creating a target from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  def apply[T](t: Task[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Target.Internal.targetTaskImpl[T]

  /**
   * A target is the most common [[Task]] a user would encounter, commonly
   * defined using the `def foo = Task {...}` syntax. [[TargetImpl]]s require that their
   * return type is JSON serializable. In return, they automatically cache their
   * return value to disk, only re-computing if upstream [[Task]]s change
   */
  implicit def apply[T](t: T)(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Internal.targetImpl[T]

  implicit def apply[T](t: Result[T])(implicit rw: RW[T], ctx: mill.define.Ctx): Task.Simple[T] =
    macro Internal.targetResultImpl[T]

  object Internal {
    private def isPrivateTargetOption(c: Context): c.Expr[Option[Boolean]] = {
      import c.universe._
      if (c.internal.enclosingOwner.isPrivate) reify(Some(true))
      else reify(Some(false))
    }

    def targetImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(
        rw: c.Expr[RW[T]],
        ctx: c.Expr[mill.define.Ctx]
    ): c.Expr[Task.Simple[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      val lhs = Applicative.impl0[Task, T, mill.api.Ctx](c)(reify(Result.create(t.splice)).tree)

      mill.define.Cacher.impl0[Task.Simple[T]](c)(
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

    def targetResultImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Result[T]])(
        rw: c.Expr[RW[T]],
        ctx: c.Expr[mill.define.Ctx]
    ): c.Expr[Task.Simple[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Task.Simple[T]](c)(
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
    def persistentTargetResultImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Result[T]])(
        rw: c.Expr[RW[T]],
        ctx: c.Expr[mill.define.Ctx]
    ): c.Expr[Task.Simple[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Task.Simple[T]](c)(
        reify {
          val s1 = Applicative.impl0[Task, T, mill.api.Ctx](c)(t.tree).splice
          val c1 = ctx.splice
          val r1 = rw.splice
          val t1 = taskIsPrivate.splice
          val applyFactory = c.prefix.splice.asInstanceOf[Task.ApplyFactory]
          if (applyFactory.persistent) {
            new PersistentImpl[T](s1, c1, r1, t1, applyFactory.deferredGeneratedSourceRoots)
          } else {
            new TargetImpl[T](s1, c1, r1, t1, applyFactory.deferredGeneratedSourceRoots)
          }
        }
      )
    }

    def targetTaskImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[Task[T]])(
        rw: c.Expr[RW[T]],
        ctx: c.Expr[mill.define.Ctx]
    ): c.Expr[Task.Simple[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Task.Simple[T]](c)(
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

    def sourcesImpl1(c: Context)(values: c.Expr[Result[os.Path]]*)(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Task.Simple[Seq[PathRef]]] = {
      import c.universe._
      val wrapped =
        for (value <- values.toList)
          yield Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(
            reify(value.splice.map(PathRef(_))).tree
          ).tree

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[SourcesImpl](c)(
        reify(
          new SourcesImpl(
            Target.sequence(c.Expr[List[Task[PathRef]]](q"_root_.scala.List(..$wrapped)").splice),
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def sourcesImpl2(c: Context)(values: c.Expr[Result[Seq[PathRef]]])(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Task.Simple[Seq[PathRef]]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[SourcesImpl](c)(
        reify(
          new SourcesImpl(
            Applicative.impl0[Task, Seq[PathRef], mill.api.Ctx](c)(values.tree).splice,
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def sourcesImpl3(c: Context)(values: c.Expr[os.SubPath]*)(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Task.Simple[Seq[PathRef]]] = {
      import c.universe._
      val wrapped =
        for (value <- values.toList)
          yield Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(
            reify(Result.Success(PathRef(ctx.splice.millSourcePath / value.splice))).tree
          ).tree

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[SourcesImpl](c)(
        reify(
          new SourcesImpl(
            Target.sequence(c.Expr[List[Task[PathRef]]](q"_root_.scala.List(..$wrapped)").splice),
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def sourceImpl1(c: Context)(value: c.Expr[Result[os.Path]])(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Task.Simple[PathRef]] = {
      import c.universe._

      val wrapped =
        Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(
          reify(value.splice.map(PathRef(_))).tree
        )

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Task.Simple[PathRef]](c)(
        reify(
          new SourceImpl(
            wrapped.splice,
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def sourceImpl2(c: Context)(value: c.Expr[Result[PathRef]])(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Task.Simple[PathRef]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Task.Simple[PathRef]](c)(
        reify(
          new SourceImpl(
            Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(value.tree).splice,
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def sourceImpl3(c: Context)(value: c.Expr[os.SubPath])(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Task.Simple[PathRef]] = {
      import c.universe._

      val wrapped =
        Applicative.impl0[Task, PathRef, mill.api.Ctx](c)(
          reify(Result.Success(PathRef(ctx.splice.millSourcePath / value.splice))).tree
        )

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Task.Simple[PathRef]](c)(
        reify(
          new SourceImpl(
            wrapped.splice,
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def inputImpl[T: c.WeakTypeTag](c: Context)(value: c.Expr[T])(
        w: c.Expr[upickle.default.Writer[T]],
        ctx: c.Expr[mill.define.Ctx]
    ): c.Expr[Task.Simple[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[InputImpl[T]](c)(
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

    def serialCommandImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(
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
          taskIsPrivate.splice,
          exclusive = c.prefix.splice.asInstanceOf[Task.CommandFactory].exclusive,
          persistent = c.prefix.splice.asInstanceOf[Task.CommandFactory].persistent
        )
      )
    }

    def workerImpl1[T: c.WeakTypeTag](c: Context)(t: c.Expr[Task[T]])(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Worker[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Worker[T]](c)(
        reify(
          new Worker[T](t.splice, ctx.splice, taskIsPrivate.splice)
        )
      )
    }

    def workerImpl2[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(ctx: c.Expr[mill.define.Ctx])
        : c.Expr[Worker[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[Worker[T]](c)(
        reify(
          new Worker[T](
            Applicative.impl[Task, T, mill.api.Ctx](c)(t).splice,
            ctx.splice,
            taskIsPrivate.splice
          )
        )
      )
    }

    def persistentImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(
        rw: c.Expr[RW[T]],
        ctx: c.Expr[mill.define.Ctx]
    ): c.Expr[PersistentImpl[T]] = {
      import c.universe._

      val taskIsPrivate = isPrivateTargetOption(c)

      mill.define.Cacher.impl0[PersistentImpl[T]](c)(
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
  }

}

/**
 * The [[mill.define.Task.Simple]] companion object, usually aliased as [[T]],
 * provides most of the helper methods and macros used to build task graphs.
 * methods like `Task.`[[apply]], `Task.`[[sources]], `Task.`[[command]] allow you to
 * define the tasks, while methods like `Task.`[[dest]], `Task.`[[log]] or
 * `Task.`[[env]] provide the core APIs that are provided to a task implementation
 */
class TaskBase extends Applicative.Applyer[Task, Task, Result, mill.api.Ctx]
    with TaskBase.TraverseCtxHolder {

  /**
   * `Task.dest` is a unique `os.Path` (e.g. `out/classFiles.dest/` or `out/run.dest/`)
   * that is assigned to every Target or Command. It is cleared before your
   * task runs, and you can use it as a scratch space for temporary files or
   * a place to put returned artifacts. This is guaranteed to be unique for
   * every Target or Command, so you can be sure that you will not collide or
   * interfere with anyone else writing to those same paths.
   */
  def dest(implicit ctx: mill.api.Ctx.Dest): os.Path = ctx.dest

  /**
   * `Task.log` is the default logger provided for every task. While your task is running,
   * `System.out` and `System.in` are also redirected to this logger. The logs for a
   * task are streamed to standard out/error as you would expect, but each task's
   * specific output is also streamed to a log file on disk, e.g. `out/run.log` or
   * `out/classFiles.log` for you to inspect later.
   *
   * Messages logged with `log.debug` appear by default only in the log files.
   * You can use the `--debug` option when running mill to show them on the console too.
   */
  def log(implicit ctx: mill.api.Ctx.Log): Logger = ctx.log

  /**
   * Returns the implicit [[mill.api.Ctx.Home.home]] in scope.
   */
  def home(implicit ctx: mill.api.Ctx.Home): os.Path = ctx.home

  /**
   * `Task.env` is the environment variable map passed to the Mill command when
   * it is run; typically used inside a `Task.Input` to ensure any changes in
   * the env vars are properly detected.
   *
   * Note that you should not use `sys.env`, as Mill's long-lived server
   * process means that `sys.env` variables may not be up to date.
   */
  def env(implicit ctx: mill.api.Ctx.Env): Map[String, String] = ctx.env

  /**
   * Returns the implicit [[mill.api.Ctx.Args.args]] in scope.
   */
  def args(implicit ctx: mill.api.Ctx.Args): IndexedSeq[_] = ctx.args

  /**
   * Report test results to BSP for IDE integration
   */
  def testReporter(implicit ctx: mill.api.Ctx): TestReporter = ctx.testReporter

  /**
   * Report build results to BSP for IDE integration
   */
  def reporter(implicit ctx: mill.api.Ctx): Int => Option[CompileProblemReporter] = ctx.reporter

  /**
   * This is the `os.Path` pointing to the project root directory.
   *
   * This is the preferred access to the project directory, and should
   * always be preferred over `os.pwd`* (which might also point to the
   * project directory in classic cli scenarios, but might not in other
   * use cases like BSP or LSP server usage).
   */
  @deprecated("Use mill.api.BuildCtx.workspaceRoot instead", "Mill 0.12.17")
  def workspace(implicit ctx: mill.api.Ctx): os.Path = ctx.workspace

  /**
   * This is the `os.Path` pointing to the project's `out` directory.
   */
  def out(implicit ctx: mill.api.Ctx): os.Path = ctx.out

  /**
   * Provides the `.fork.async` and `.fork.await` APIs for spawning and joining
   * async futures within your task in a Mill-friendly manner.
   */
  def fork(implicit ctx: mill.api.Ctx): mill.api.Ctx.Fork.Api = ctx.fork

  /**
   * Converts a `Seq[Task[T]]` into a `Task[Seq[T]]`
   */
  def sequence[T](source: Seq[Task[T]]): Task[Seq[T]] = new Task.Sequence[T](source)

  /**
   * Converts a `Seq[T]` into a `Task[Seq[V]]` using the given `f: T => Task[V]`
   */
  def traverse[T, V](source: Seq[T])(f: T => Task[V]): Task[Seq[V]] = {
    new Task.Sequence[V](source.map(f))
  }
}

object TaskBase {
  trait TraverseCtxHolder {

    /**
     * A variant of [[traverse]] that also provides the [[mill.api.Ctx]] to the
     * function [[f]]
     */
    def traverseCtx[I, R](xs: Seq[Task[I]])(f: (IndexedSeq[I], mill.api.Ctx) => Result[R])
        : Task[R] = {
      new Task.TraverseCtx[I, R](xs, f)
    }
  }
}

class TargetImpl[+T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val readWriter: RW[_],
    val isPrivate: Option[Boolean],
    override val deferredGeneratedSourceRoots: Seq[os.SubPath]
) extends Task.Simple[T] {

  // Added for bincompat
  def this(
      t: Task[T],
      ctx0: mill.define.Ctx,
      readWriter: RW[_],
      isPrivate: Option[Boolean]
  ) = this(t, ctx0, readWriter, isPrivate, Seq.empty)

  override def asTarget: Option[Task.Simple[T]] = Some(this)
  // FIXME: deprecated return type: Change to Option
  override def readWriterOpt: Some[RW[_]] = Some(readWriter)
}

class PersistentImpl[+T](
    t: Task[T],
    ctx0: mill.define.Ctx,
    readWriter: RW[_],
    isPrivate: Option[Boolean],
    deferredGeneratedSourceRoots: Seq[os.SubPath]
) extends TargetImpl[T](t, ctx0, readWriter, isPrivate, deferredGeneratedSourceRoots) {
  // Added for bincompat
  def this(t: Task[T], ctx0: mill.define.Ctx, readWriter: RW[_], isPrivate: Option[Boolean]) =
    this(t, ctx0, readWriter, isPrivate, Seq.empty)

  override def flushDest = false
}

class Command[+T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val writer: W[_],
    val cls: Class[_],
    val isPrivate: Option[Boolean],
    val exclusive: Boolean,
    val persistent: Boolean
) extends NamedTask[T] {
  override def flushDest: Boolean = !persistent

  /** Binary compatibility forwarder. */
  def this(
      t: Task[T],
      ctx0: mill.define.Ctx,
      writer: W[_],
      cls: Class[_],
      isPrivate: Option[Boolean]
  ) = this(t, ctx0, writer, cls, isPrivate, exclusive = false, persistent = false)

  /** Binary compatibility forwarder. */
  def this(
      t: Task[T],
      ctx0: mill.define.Ctx,
      writer: W[_],
      cls: Class[_],
      isPrivate: Option[Boolean],
      exclusive: Boolean
  ) = this(t, ctx0, writer, cls, isPrivate, exclusive = exclusive, persistent = false)

  override def asCommand: Some[Command[T]] = Some(this)
  // FIXME: deprecated return type: Change to Option
  override def writerOpt: Some[W[_]] = Some(writer)
}

class Worker[+T](val t: Task[T], val ctx0: mill.define.Ctx, val isPrivate: Option[Boolean])
    extends NamedTask[T] {
  override def flushDest = false
  override def asWorker: Some[Worker[T]] = Some(this)
}

class InputImpl[T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val writer: upickle.default.Writer[_],
    val isPrivate: Option[Boolean]
) extends Task.Simple[T] {
  override def sideHash: Int = util.Random.nextInt()
  // FIXME: deprecated return type: Change to Option
  override def writerOpt: Some[W[_]] = Some(writer)
}

class SourcesImpl(t: Task[Seq[PathRef]], ctx0: mill.define.Ctx, isPrivate: Option[Boolean])
    extends InputImpl[Seq[PathRef]](
      t,
      ctx0,
      upickle.default.readwriter[Seq[PathRef]],
      isPrivate
    ) {
  override def readWriterOpt: Some[RW[Seq[PathRef]]] =
    Some(upickle.default.readwriter[Seq[PathRef]])
}

class SourceImpl(t: Task[PathRef], ctx0: mill.define.Ctx, isPrivate: Option[Boolean])
    extends InputImpl[PathRef](
      t,
      ctx0,
      upickle.default.readwriter[PathRef],
      isPrivate
    ) {
  override def readWriterOpt: Some[RW[PathRef]] = Some(upickle.default.readwriter[PathRef])
}
