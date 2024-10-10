package mill.define

import mill.api.{CompileProblemReporter, Logger, PathRef, Result, TestReporter}
import mill.define.Applicative.Applyable
import upickle.default.{ReadWriter => RW, Writer => W}

import TaskBase.TraverseCtxHolder

import scala.language.implicitConversions
import scala.quoted.*

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

object Task extends TaskBase {

  /**
   * A specialization of [[InputImpl]] defined via `Task.Sources`, [[SourcesImpl]]
   * uses [[PathRef]]s to compute a signature for a set of source files and
   * folders.
   *
   * This is most used when detecting changes in source code: when you edit a
   * file and run `mill compile`, it is the `Task.Sources` that re-computes the
   * signature for you source files/folders and decides whether or not downstream
   * [[TargetImpl]]s need to be invalidated and re-computed.
   */
  inline def Sources(inline values: Result[os.Path]*)(implicit
      inline ctx: mill.define.Ctx
  ): Target[Seq[PathRef]] = ${ Target.Internal.sourcesImpl1('values)('ctx, 'this) }

  inline def Sources(inline values: Result[Seq[PathRef]])(implicit
      inline ctx: mill.define.Ctx
  ): Target[Seq[PathRef]] =
    ${ Target.Internal.sourcesImpl2('values)('ctx, 'this) }

  /**
   * Similar to [[Source]], but only for a single source file or folder. Defined
   * using `Task.Source`.
   */
  inline def Source(inline value: Result[os.Path])(implicit
      inline ctx: mill.define.Ctx
  ): Target[PathRef] =
    ${ Target.Internal.sourceImpl1('value)('ctx, 'this) }

  @annotation.targetName("SourceRef")
  inline def Source(inline value: Result[PathRef])(implicit
      inline ctx: mill.define.Ctx
  ): Target[PathRef] =
    ${ Target.Internal.sourceImpl2('value)('ctx, 'this) }

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
  inline def Input[T](inline value: Result[T])(implicit
      inline w: upickle.default.Writer[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Target.Internal.inputImpl[T]('value)('w, 'ctx, 'this) }

  /**
   * [[Command]]s are only [[NamedTask]]s defined using
   * `def foo() = Task.Command{...}` and are typically called from the
   * command-line. Unlike other [[NamedTask]]s, [[Command]]s can be defined to
   * take arguments that are automatically converted to command-line
   * arguments, as long as an implicit [[mainargs.TokensReader]] is available.
   */
  inline def Command[T](inline t: Result[T])(implicit
      inline w: W[T],
      inline ctx: mill.define.Ctx,
      inline cls: EnclosingClass
  ): Command[T] = ${ Target.Internal.commandImpl[T]('t)('w, 'ctx, 'cls, 'this) }

  /**
   * @param exclusive Exclusive commands run serially at the end of an evaluation,
   *                  without any other tasks running parallel, and without the
   *                  terminal logging prefixes that are applied to normal tasks.
   *                  These are normally used for "top level" commands which are
   *                  run directly to perform some action or display some output
   *                  to the user.
   */
  def Command(
      t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      exclusive: Boolean = false
  ): CommandFactory = new CommandFactory(exclusive)
  class CommandFactory private[mill] (val exclusive: Boolean) extends TaskBase.TraverseCtxHolder {
    inline def apply[T](inline t: Result[T])(implicit
        inline w: W[T],
        inline ctx: mill.define.Ctx,
        inline cls: EnclosingClass
    ): Command[T] = ${ Target.Internal.serialCommandImpl[T]('t)('w, 'ctx, 'cls, 'this) }
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
  inline def Worker[T](inline t: Result[T])(implicit inline ctx: mill.define.Ctx): Worker[T] =
    ${ Target.Internal.workerImpl2[T]('t)('ctx, 'this) }

  /**
   * Creates an anonymous `Task`. These depend on other tasks and
   * be-depended-upon by other tasks, but cannot be run directly from the
   * command line and do not perform any caching. Typically used as helpers to
   * implement `Task{...}` targets.
   */
  inline def Anon[T](inline t: Result[T]): Task[T] =
    ${ Target.Internal.anonTaskImpl[T]('t)('this) }

  @deprecated(
    "Creating a target from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  inline def apply[T](inline t: Task[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Target.Internal.targetTaskImpl[T]('t)('rw, 'ctx) }

  inline def apply[T](inline t: T)(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Target.Internal.targetImpl[T]('t)('rw, 'ctx, 'this) }

  inline def apply[T](inline t: Result[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Target.Internal.targetResultImpl[T]('t)('rw, 'ctx, 'this) }

  /**
   * Persistent tasks are defined using
   * the `Task(persistent = true){...}` syntax. The main difference is that while
   * [[TargetImpl]] deletes the `T.dest` folder in between runs,
   * [[PersistentImpl]] preserves it. This lets the user make use of files on
   * disk that persistent between runs of the task, e.g. to implement their own
   * fine-grained caching beyond what Mill provides by default.
   *
   * Note that the user defining a `Task(persistent = true)` task is taking on the
   * responsibility of ensuring that their implementation is idempotent, i.e.
   * that it computes the same result whether or not there is data in `T.dest`.
   * Violating that invariant can result in confusing mis-behaviors
   */
  def apply(
      t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      persistent: Boolean = false
  ): ApplyFactory = new ApplyFactory(persistent)
  class ApplyFactory private[mill] (val persistent: Boolean) extends TaskBase.TraverseCtxHolder {
    inline def apply[T](inline t: Result[T])(implicit
        inline rw: RW[T],
        inline ctx: mill.define.Ctx
    ): Target[T] = ${ Target.Internal.persistentTargetResultImpl[T]('t)('rw, 'ctx, 'this) }
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
 * `Task.Anon` or `T.traverse` etc. instances
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
trait Target[+T] extends NamedTask[T]

object Target extends TaskBase {
  @deprecated("Use Task(persistent = true){...} instead", "Mill after 0.12.0-RC1")
  inline def persistent[T](inline t: Result[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Internal.persistentImpl[T]('t)('rw, 'ctx, 'this) }

  @deprecated("Use Task.Sources instead", "Mill after 0.12.0-RC1")
  inline def sources(inline values: Result[os.Path]*)(implicit
      inline ctx: mill.define.Ctx
  ): Target[Seq[PathRef]] = ${ Internal.sourcesImpl1('values)('ctx, 'this) }

  @deprecated("Use Task.Sources instead", "Mill after 0.12.0-RC1")
  inline def sources(inline values: Result[Seq[PathRef]])(implicit
      inline ctx: mill.define.Ctx
  ): Target[Seq[PathRef]] =
    ${ Internal.sourcesImpl2('values)('ctx, 'this) }

  @deprecated("Use Task.Source instead", "Mill after 0.12.0-RC1")
  inline def source(inline value: Result[os.Path])(implicit
      inline ctx: mill.define.Ctx
  ): Target[PathRef] =
    ${ Internal.sourceImpl1('value)('ctx, 'this) }

  @deprecated("Use Task.Source instead", "Mill after 0.12.0-RC1")
  @annotation.targetName("sourceRef")
  inline def source(inline value: Result[PathRef])(implicit
      inline ctx: mill.define.Ctx
  ): Target[PathRef] =
    ${ Internal.sourceImpl2('value)('ctx, 'this) }

  @deprecated("Use Task.Input instead", "Mill after 0.12.0-RC1")
  inline def input[T](inline value: Result[T])(implicit
      inline w: upickle.default.Writer[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Internal.inputImpl[T]('value)('w, 'ctx, 'this) }

  @deprecated(
    "Creating a command from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  inline def command[T](inline t: Task[T])(implicit
      inline ctx: mill.define.Ctx,
      inline w: W[T],
      inline cls: EnclosingClass
  ): Command[T] = ${ Internal.commandFromTask[T]('t)('ctx, 'w, 'cls) }

  @deprecated("Use Task.Command instead", "Mill after 0.12.0-RC1")
  inline def command[T](inline t: Result[T])(implicit
      inline w: W[T],
      inline ctx: mill.define.Ctx,
      inline cls: EnclosingClass
  ): Command[T] = ${ Internal.commandImpl[T]('t)('w, 'ctx, 'cls, 'this) }

  @deprecated(
    "Creating a worker from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  inline def worker[T](inline t: Task[T])(implicit inline ctx: mill.define.Ctx): Worker[T] =
    ${ Internal.workerImpl1[T]('t)('ctx) }

  @deprecated("Use Task.Worker instead", "Mill after 0.12.0-RC1")
  inline def worker[T](inline t: Result[T])(implicit inline ctx: mill.define.Ctx): Worker[T] =
    ${ Internal.workerImpl2[T]('t)('ctx, 'this) }

  @deprecated("Use Task.Anon instead", "Mill after 0.12.0-RC2")
  inline def task[T](inline t: Result[T]): Task[T] =
    ${ Target.Internal.anonTaskImpl[T]('t)('this) }

  @deprecated(
    "Creating a target from a task is deprecated. You most likely forgot a parenthesis pair `()`",
    "Mill after 0.12.0-RC1"
  )
  inline def apply[T](inline t: Task[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Internal.targetTaskImpl[T]('t)('rw, 'ctx) }

  /**
   * A target is the most common [[Task]] a user would encounter, commonly
   * defined using the `def foo = Task {...}` syntax. [[TargetImpl]]s require that their
   * return type is JSON serializable. In return they automatically caches their
   * return value to disk, only re-computing if upstream [[Task]]s change
   */
  implicit inline def apply[T](inline t: T)(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Internal.targetImpl[T]('t)('rw, 'ctx, 'this) }

  implicit inline def apply[T](inline t: Result[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ Internal.targetResultImpl[T]('t)('rw, 'ctx, 'this) }

  object Internal {
    private def withMacroOwner[T](using Quotes)(op: quotes.reflect.Symbol => T): T = {
      import quotes.reflect.*

      // In Scala 3, the top level splice of a macro is owned by a symbol called "macro" with the macro flag set,
      // but not the method flag.
      def isMacroOwner(sym: Symbol)(using Quotes): Boolean =
        sym.name == "macro" && sym.flags.is(Flags.Macro | Flags.Synthetic) && !sym.flags.is(
          Flags.Method
        )

      def loop(owner: Symbol): T =
        if owner.isPackageDef || owner == Symbol.noSymbol then
          report.errorAndAbort(
            "Cannot find the owner of the macro expansion",
            Position.ofMacroExpansion
          )
        else if isMacroOwner(owner) then op(owner.owner) // Skip the "macro" owner
        else loop(owner.owner)

      loop(Symbol.spliceOwner)
    }

    private def isPrivateTargetOption()(using Quotes): Expr[Option[Boolean]] = withMacroOwner {
      owner =>
        import quotes.reflect.*
        if owner.flags.is(Flags.Private) then Expr(Some(true))
        else Expr(Some(false))
    }

    private def traverseCtxExpr[R: Type](caller: Expr[TraverseCtxHolder])(
        args: Expr[Seq[Task[Any]]],
        fn: Expr[(IndexedSeq[Any], mill.api.Ctx) => Result[R]]
    )(using Quotes): Expr[Task[R]] =
      '{ $caller.traverseCtx[Any, R]($args)($fn) }

    def anonTaskImpl[T: Type](t: Expr[Result[T]])(
        caller: Expr[TraverseCtxHolder]
    )(using Quotes): Expr[Task[T]] = {
      Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)
    }

    def targetImpl[T: Type](t: Expr[T])(
        rw: Expr[RW[T]],
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    )(using Quotes): Expr[Target[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs =
        Applicative.impl[Task, Task, Result, T, mill.api.Ctx](
          traverseCtxExpr(caller),
          '{ Result.create($t) }
        )

      mill.moduledefs.Cacher.impl0[Target[T]](
        '{
          new TargetImpl[T](
            $lhs,
            $ctx,
            $rw,
            $taskIsPrivate
          )
        }
      )
    }

    def targetResultImpl[T: Type](using Quotes)(t: Expr[Result[T]])(
        rw: Expr[RW[T]],
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Target[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs = Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)

      mill.moduledefs.Cacher.impl0[Target[T]](
        '{
          new TargetImpl[T](
            $lhs,
            $ctx,
            $rw,
            $taskIsPrivate
          )
        }
      )
    }

    def persistentTargetResultImpl[T: Type](using Quotes)(t: Expr[Result[T]])(
        rw: Expr[RW[T]],
        ctx: Expr[mill.define.Ctx],
        caller: Expr[Task.ApplyFactory]
    ): Expr[Target[T]] = {
      val taskIsPrivate = isPrivateTargetOption()
      val lhs = Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)

      mill.moduledefs.Cacher.impl0[Target[T]](
        '{
          if $caller.persistent then
            new PersistentImpl[T](
              $lhs,
              $ctx,
              $rw,
              $taskIsPrivate
            )
          else
            new TargetImpl[T](
              $lhs,
              $ctx,
              $rw,
              $taskIsPrivate
            )
        }
      )
    }

    def targetTaskImpl[T: Type](using Quotes)(t: Expr[Task[T]])(
        rw: Expr[RW[T]],
        ctx: Expr[mill.define.Ctx]
    ): Expr[Target[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      mill.moduledefs.Cacher.impl0[Target[T]](
        '{
          new TargetImpl[T](
            $t,
            $ctx,
            $rw,
            $taskIsPrivate
          )
        }
      )
    }

    def sourcesImpl1(using Quotes)(values: Expr[Seq[Result[os.Path]]])(
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Target[Seq[PathRef]]] = {

      val unwrapped = Varargs.unapply(values).get

      val wrapped =
        for (value <- unwrapped.toList)
          yield Applicative.impl[Task, Task, Result, PathRef, mill.api.Ctx](
            traverseCtxExpr(caller),
            '{ $value.map(PathRef(_)) }
          )

      val taskIsPrivate = isPrivateTargetOption()

      mill.moduledefs.Cacher.impl0[SourcesImpl](
        '{
          new SourcesImpl(
            Target.sequence(List(${ Varargs(wrapped) }*)),
            $ctx,
            $taskIsPrivate
          )
        }
      )
    }

    def sourcesImpl2(using Quotes)(
        values: Expr[Result[Seq[PathRef]]]
    )(
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Target[Seq[PathRef]]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs = Applicative.impl[Task, Task, Result, Seq[PathRef], mill.api.Ctx](
        traverseCtxExpr(caller),
        values
      )

      mill.moduledefs.Cacher.impl0[SourcesImpl](
        '{
          new SourcesImpl(
            $lhs,
            $ctx,
            $taskIsPrivate
          )
        }
      )
    }

    def sourceImpl1(using
        Quotes
    )(value: Expr[Result[os.Path]])(
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Target[PathRef]] = {
      val wrapped =
        Applicative.impl[Task, Task, Result, PathRef, mill.api.Ctx](
          traverseCtxExpr(caller),
          '{ $value.map(PathRef(_)) }
        )

      val taskIsPrivate = isPrivateTargetOption()

      mill.moduledefs.Cacher.impl0[Target[PathRef]](
        '{
          new SourceImpl(
            $wrapped,
            $ctx,
            $taskIsPrivate
          )
        }
      )
    }

    def sourceImpl2(using
        Quotes
    )(value: Expr[Result[PathRef]])(
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Target[PathRef]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs =
        Applicative.impl[Task, Task, Result, PathRef, mill.api.Ctx](traverseCtxExpr(caller), value)
      mill.moduledefs.Cacher.impl0[Target[PathRef]](
        '{
          new SourceImpl(
            $lhs,
            $ctx,
            $taskIsPrivate
          )
        }
      )
    }

    def inputImpl[T: Type](using Quotes)(value: Expr[Result[T]])(
        w: Expr[upickle.default.Writer[T]],
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Target[T]] = {
      val taskIsPrivate = isPrivateTargetOption()
      val lhs =
        Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), value)

      mill.moduledefs.Cacher.impl0[InputImpl[T]](
        '{
          new InputImpl[T](
            $lhs,
            $ctx,
            $w,
            $taskIsPrivate
          )
        }
      )
    }

    def commandFromTask[T: Type](using Quotes)(t: Expr[Task[T]])(
        ctx: Expr[mill.define.Ctx],
        w: Expr[W[T]],
        cls: Expr[EnclosingClass]
    ): Expr[Command[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      '{
        new Command[T](
          $t,
          $ctx,
          $w,
          $cls.value,
          $taskIsPrivate
        )
      }
    }

    def commandImpl[T: Type](using Quotes)(t: Expr[Result[T]])(
        w: Expr[W[T]],
        ctx: Expr[mill.define.Ctx],
        cls: Expr[EnclosingClass],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Command[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs = Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)
      '{
        new Command[T](
          $lhs,
          $ctx,
          $w,
          $cls.value,
          $taskIsPrivate,
          exclusive = false
        )
      }
    }

    def serialCommandImpl[T: Type](using Quotes)(t: Expr[Result[T]])(
        w: Expr[W[T]],
        ctx: Expr[mill.define.Ctx],
        cls: Expr[EnclosingClass],
        caller: Expr[Task.CommandFactory]
    ): Expr[Command[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs = Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)
      '{
        new Command[T](
          $lhs,
          $ctx,
          $w,
          $cls.value,
          $taskIsPrivate,
          exclusive = $caller.exclusive
        )
      }
    }

    def workerImpl1[T: Type](using
        Quotes
    )(t: Expr[Task[T]])(ctx: Expr[mill.define.Ctx]): Expr[Worker[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      mill.moduledefs.Cacher.impl0[Worker[T]](
        '{
          new Worker[T]($t, $ctx, $taskIsPrivate)
        }
      )
    }

    def workerImpl2[T: Type](using
        Quotes
    )(t: Expr[Result[T]])(
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[Worker[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs = Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)

      mill.moduledefs.Cacher.impl0[Worker[T]](
        '{
          new Worker[T](
            $lhs,
            $ctx,
            $taskIsPrivate
          )
        }
      )
    }

    def persistentImpl[T: Type](using Quotes)(t: Expr[Result[T]])(
        rw: Expr[RW[T]],
        ctx: Expr[mill.define.Ctx],
        caller: Expr[TraverseCtxHolder]
    ): Expr[PersistentImpl[T]] = {
      val taskIsPrivate = isPrivateTargetOption()

      val lhs = Applicative.impl[Task, Task, Result, T, mill.api.Ctx](traverseCtxExpr(caller), t)

      mill.moduledefs.Cacher.impl0[PersistentImpl[T]](
        '{
          new PersistentImpl[T](
            $lhs,
            $ctx,
            $rw,
            $taskIsPrivate
          )
        }
      )
    }
  }

}

/**
 * The [[mill.define.Target]] companion object, usually aliased as [[T]],
 * provides most of the helper methods and macros used to build task graphs.
 * methods like `T.`[[apply]], `T.`[[sources]], `T.`[[command]] allow you to
 * define the tasks, while methods like `T.`[[dest]], `T.`[[log]] or
 * `T.`[[env]] provide the core APIs that are provided to a task implementation
 */
class TaskBase extends Applicative.Applyer[Task, Task, Result, mill.api.Ctx]
    with TaskBase.TraverseCtxHolder {

  /**
   * `T.dest` is a unique `os.Path` (e.g. `out/classFiles.dest/` or `out/run.dest/`)
   * that is assigned to every Target or Command. It is cleared before your
   * task runs, and you can use it as a scratch space for temporary files or
   * a place to put returned artifacts. This is guaranteed to be unique for
   * every Target or Command, so you can be sure that you will not collide or
   * interfere with anyone else writing to those same paths.
   */
  def dest(implicit ctx: mill.api.Ctx.Dest): os.Path = ctx.dest

  /**
   * `T.log` is the default logger provided for every task. While your task is running,
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
   * `T.env` is the environment variable map passed to the Mill command when
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
   * always be prefered over `os.pwd`* (which might also point to the
   * project directory in classic cli scenarios, but might not in other
   * use cases like BSP or LSP server usage).
   */
  def workspace(implicit ctx: mill.api.Ctx): os.Path = ctx.workspace

  /**
   * Provides the `.fork.async` and `.fork.await` APIs for spawning and joining
   * async futures within your task in a Mill-friendly mannter
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
    val isPrivate: Option[Boolean]
) extends Target[T] {
  override def asTarget: Option[Target[T]] = Some(this)
  // FIXME: deprecated return type: Change to Option
  override def readWriterOpt: Some[RW[_]] = Some(readWriter)
}

class PersistentImpl[+T](
    t: Task[T],
    ctx0: mill.define.Ctx,
    readWriter: RW[_],
    isPrivate: Option[Boolean]
) extends TargetImpl[T](t, ctx0, readWriter, isPrivate) {
  override def flushDest = false
}

class Command[+T](
    val t: Task[T],
    val ctx0: mill.define.Ctx,
    val writer: W[_],
    val cls: Class[_],
    val isPrivate: Option[Boolean],
    val exclusive: Boolean
) extends NamedTask[T] {
  def this(
      t: Task[T],
      ctx0: mill.define.Ctx,
      writer: W[_],
      cls: Class[_],
      isPrivate: Option[Boolean]
  ) = this(t, ctx0, writer, cls, isPrivate, false)
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
) extends Target[T] {
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
