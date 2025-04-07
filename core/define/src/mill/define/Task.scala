package mill.define

import mill.api.PathRef
import mill.runner.api.{CompileProblemReporter, Logger, Result, TestReporter, TaskApi, NamedTaskApi}
import mill.define.internal.Applicative.Applyable
import mill.define.internal.Cacher
import upickle.default.{ReadWriter as RW, Writer as W}
import mill.define.internal.{Applicative, NamedParameterOnlyDummy}

import scala.language.implicitConversions
import scala.quoted.*

/**
 * Models a single node in the Mill build graph, with a list of inputs and a
 * single output of type [[T]].
 *
 * Generally not instantiated manually, but instead constructed via the
 * [[Target.apply]] & similar macros.
 */
sealed abstract class Task[+T] extends Task.Ops[T] with Applyable[Task, T] with TaskApi[T] {

  /**
   * What other tasks does this task depend on?
   */
  val inputs: Seq[Task[?]]

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
   * Whether or not this [[Task]] deletes the `Task.dest` folder between runs
   */
  def persistent: Boolean = false

  def asTarget: Option[Target[T]] = None
  def asCommand: Option[Command[T]] = None
  def asWorker: Option[Worker[T]] = None
  def isExclusiveCommand: Boolean = this match {
    case c: Command[_] if c.exclusive => true
    case _ => false
  }
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
  ): Target[Seq[PathRef]] = ${
    TaskMacros.sourcesImpl('{ Result.sequence(values.map(_.map(PathRef(_)))) })('ctx)
  }

  inline def Sources(inline values: os.SubPath*)(implicit
      inline ctx: mill.define.Ctx,
      dummy: Boolean = true
  ): Target[Seq[PathRef]] = ${
    TaskMacros.sourcesImpl(
      '{ values.map(sub => PathRef(ctx.millSourcePath / os.up / os.PathChunk.SubPathChunk(sub))) }
    )('ctx)
  }

  /**
   * Similar to [[Sources]], but only for a single source file or folder. Defined
   * using `Task.Source`.
   */
  inline def Source(inline value: Result[os.Path])(implicit
      inline ctx: mill.define.Ctx
  ): Target[PathRef] =
    ${ TaskMacros.sourceImpl('{ value.map(PathRef(_)) })('ctx) }

  inline def Source(inline value: os.SubPath)(implicit
      inline ctx: mill.define.Ctx
  ): Target[PathRef] =
    ${ TaskMacros.sourceImpl('{ PathRef(ctx.millSourcePath / os.up / value) })('ctx) }

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
    ${ TaskMacros.inputImpl[T]('value)('w, 'ctx) }

  /**
   * [[Command]]s are only [[NamedTask]]s defined using
   * `def foo() = Task.Command{...}` and are typically called from the
   * command-line. Unlike other [[NamedTask]]s, [[Command]]s can be defined to
   * take arguments that are automatically converted to command-line
   * arguments, as long as an implicit [[mainargs.TokensReader]] is available.
   */
  inline def Command[T](inline t: Result[T])(implicit
      inline w: W[T],
      inline ctx: mill.define.Ctx
  ): Command[T] = ${ TaskMacros.commandImpl[T]('t)('w, 'ctx, exclusive = '{ false }) }

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
  class CommandFactory private[mill] (val exclusive: Boolean) {
    inline def apply[T](inline t: Result[T])(implicit
        inline w: W[T],
        inline ctx: mill.define.Ctx
    ): Command[T] = ${ TaskMacros.commandImpl[T]('t)('w, 'ctx, '{ this.exclusive }) }
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
    ${ TaskMacros.workerImpl2[T]('t)('ctx) }

  /**
   * Creates an anonymous `Task`. These depend on other tasks and
   * be-depended-upon by other tasks, but cannot be run directly from the
   * command line and do not perform any caching. Typically used as helpers to
   * implement `Task{...}` targets.
   */
  inline def Anon[T](inline t: Result[T]): Task[T] =
    ${ TaskMacros.anonTaskImpl[T]('t) }

  inline def apply[T](inline t: Result[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ TaskMacros.targetResultImpl[T]('t)('rw, 'ctx, '{ false }) }

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
   * that it computes the same result whether or not there is data in `Task.dest`.
   * Violating that invariant can result in confusing mis-behaviors
   */
  def apply(
      t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      persistent: Boolean = false
  ): ApplyFactory = new ApplyFactory(persistent)
  class ApplyFactory private[mill] (val persistent: Boolean) {
    inline def apply[T](inline t: Result[T])(implicit
        inline rw: RW[T],
        inline ctx: mill.define.Ctx
    ): Target[T] = ${ TaskMacros.targetResultImpl[T]('t)('rw, 'ctx, '{ persistent }) }
  }

  abstract class Ops[+T] { this: Task[T] =>
    def map[V](f: T => V): Task[V] = new Task.Mapped(this, f)
    def filter(f: T => Boolean): Task[T] = this
    def withFilter(f: T => Boolean): Task[T] = this
    def zip[V](other: Task[V]): Task[(T, V)] = new Task.Zipped(this, other)

  }

  private[define] class Sequence[+T](inputs0: Seq[Task[T]]) extends Task[Seq[T]] {
    val inputs: Seq[Task[?]] = inputs0
    def evaluate(ctx: mill.api.Ctx): Result[Seq[T]] = {
      for (i <- 0 until ctx.args.length)
        yield ctx.args(i).asInstanceOf[T]
    }
  }

  private[define] class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V] {
    def evaluate(ctx: mill.api.Ctx): Result[V] = f(ctx.arg(0))
    val inputs: Seq[Task[?]] = List(source)
  }

  private[define] class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)] {
    def evaluate(ctx: mill.api.Ctx): Result[(T, V)] = (ctx.arg(0), ctx.arg(1))
    val inputs: Seq[Task[?]] = List(source1, source2)
  }
}

/**
 * Represents a task that can be referenced by its path segments. `Task{...}`
 * targets, `Task.Input`, `Task.Worker`, etc. but not including anonymous
 * `Task.Anon` or `Task.traverse` etc. instances
 */
trait NamedTask[+T] extends Task[T] with NamedTaskApi[T] {

  def ctx0: mill.define.Ctx
  def isPrivate: Option[Boolean]
  def label: String = ctx.segments.value.last match {
    case Segment.Label(v) => v
    case Segment.Cross(_) => throw new IllegalArgumentException(
        "NamedTask only support a ctx with a Label segment, but found a Cross."
      )
  }
  override def toString = ctx.segments.render

  def evaluate(ctx: mill.api.Ctx): Result[T] = evaluate0(ctx.args, ctx)

  def evaluate0: (Seq[Any], mill.api.Ctx) => Result[T]

  val ctx: Ctx = ctx0

  def readWriterOpt: Option[upickle.default.ReadWriter[?]] = None

  def writerOpt: Option[upickle.default.Writer[?]] = readWriterOpt.orElse(None)
}

/**
 * A Target is a [[NamedTask]] that is cached on disk; either a
 * [[TargetImpl]] or an [[InputImpl]]
 */
trait Target[+T] extends NamedTask[T]

object Target extends TaskBase {

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
    ${ TaskMacros.targetResultImpl[T]('{ Result.Success(t) })('rw, 'ctx, '{ false }) }

  implicit inline def apply[T](inline t: Result[T])(implicit
      inline rw: RW[T],
      inline ctx: mill.define.Ctx
  ): Target[T] =
    ${ TaskMacros.targetResultImpl[T]('t)('rw, 'ctx, '{ false }) }

}

/**
 * The [[mill.define.Target]] companion object, usually aliased as [[T]],
 * provides most of the helper methods and macros used to build task graphs.
 * methods like `Task.`[[apply]], `Task.`[[sources]], `Task.`[[command]] allow you to
 * define the tasks, while methods like `Task.`[[dest]], `Task.`[[log]] or
 * `Task.`[[env]] provide the core APIs that are provided to a task implementation
 */
class TaskBase {

  /**
   * Returns the [[mill.api.Ctx]] that is available within this task
   */
  def ctx()(implicit c: mill.api.Ctx): mill.api.Ctx = c

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
  def args(implicit ctx: mill.api.Ctx.Args): IndexedSeq[?] = ctx.args

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

class TargetImpl[+T](
    val inputs: Seq[Task[Any]],
    val evaluate0: (Seq[Any], mill.api.Ctx) => Result[T],
    val ctx0: mill.define.Ctx,
    val readWriter: RW[?],
    val isPrivate: Option[Boolean],
    override val persistent: Boolean
) extends Target[T] {
  override def asTarget: Option[Target[T]] = Some(this)
  // FIXME: deprecated return type: Change to Option
  override def readWriterOpt: Some[RW[?]] = Some(readWriter)
}

class Command[+T](
    val inputs: Seq[Task[Any]],
    val evaluate0: (Seq[Any], mill.api.Ctx) => Result[T],
    val ctx0: mill.define.Ctx,
    val writer: W[?],
    val isPrivate: Option[Boolean],
    val exclusive: Boolean
) extends NamedTask[T] {

  override def asCommand: Some[Command[T]] = Some(this)
  // FIXME: deprecated return type: Change to Option
  override def writerOpt: Some[W[?]] = Some(writer)
}

class Worker[+T](
    val inputs: Seq[Task[Any]],
    val evaluate0: (Seq[Any], mill.api.Ctx) => Result[T],
    val ctx0: mill.define.Ctx,
    val isPrivate: Option[Boolean]
) extends NamedTask[T] {
  override def persistent = false
  override def asWorker: Some[Worker[T]] = Some(this)
}

class InputImpl[T](
    val evaluate0: (Seq[Any], mill.api.Ctx) => Result[T],
    val ctx0: mill.define.Ctx,
    val writer: upickle.default.Writer[?],
    val isPrivate: Option[Boolean]
) extends Target[T] {
  val inputs = Nil
  override def sideHash: Int = util.Random.nextInt()
  // FIXME: deprecated return type: Change to Option
  override def writerOpt: Some[W[?]] = Some(writer)
}

class SourcesImpl(
    evaluate0: (Seq[Any], mill.api.Ctx) => Result[Seq[PathRef]],
    ctx0: mill.define.Ctx,
    isPrivate: Option[Boolean]
) extends InputImpl[Seq[PathRef]](
      evaluate0,
      ctx0,
      upickle.default.readwriter[Seq[PathRef]],
      isPrivate
    ) {}

class SourceImpl(
    evaluate0: (Seq[Any], mill.api.Ctx) => Result[PathRef],
    ctx0: mill.define.Ctx,
    isPrivate: Option[Boolean]
) extends InputImpl[PathRef](
      evaluate0,
      ctx0,
      upickle.default.readwriter[PathRef],
      isPrivate
    ) {}

class AnonImpl[T](val inputs: Seq[Task[_]], evaluate0: (Seq[Any], mill.api.Ctx) => Result[T])
    extends Task[T] {
  def evaluate(ctx: mill.api.Ctx) = evaluate0(ctx.args, ctx)
}

private object TaskMacros {
  def appImpl[M[_]: Type, T: Type](using
      Quotes
  )(
      traverseCtx: (
          Expr[Seq[Task[Any]]],
          Expr[(Seq[Any], mill.api.Ctx) => Result[T]]
      ) => Expr[M[T]],
      t: Expr[Result[T]],
      allowTaskReferences: Boolean = true
  ): Expr[M[T]] =
    Applicative.impl[M, Task, Result, T, mill.api.Ctx](traverseCtx, t, allowTaskReferences)

  private def taskIsPrivate()(using Quotes): Expr[Option[Boolean]] =
    Cacher.withMacroOwner {
      owner =>
        import quotes.reflect.*
        if owner.flags.is(Flags.Private) then Expr(Some(true))
        else Expr(Some(false))
    }

  def anonTaskImpl[T: Type](t: Expr[Result[T]])(using Quotes): Expr[Task[T]] = {
    appImpl[Task, T]((in, ev) => '{ AnonImpl($in, $ev) }, t)
  }

  def targetResultImpl[T: Type](using
      Quotes
  )(t: Expr[Result[T]])(
      rw: Expr[RW[T]],
      ctx: Expr[mill.define.Ctx],
      persistent: Expr[Boolean]
  ): Expr[Target[T]] = {
    val expr = appImpl[Target, T](
      (in, ev) => '{ new TargetImpl[T]($in, $ev, $ctx, $rw, ${ taskIsPrivate() }, $persistent) },
      t
    )

    Cacher.impl0(expr)
  }

  def sourcesImpl(using
      Quotes
  )(
      values: Expr[Result[Seq[PathRef]]]
  )(
      ctx: Expr[mill.define.Ctx]
  ): Expr[Target[Seq[PathRef]]] = {
    val expr = appImpl[Target, Seq[PathRef]](
      (in, ev) => '{ new SourcesImpl($ev, $ctx, ${ taskIsPrivate() }) },
      values,
      allowTaskReferences = false
    )
    Cacher.impl0(expr)
  }

  def sourceImpl(using
      Quotes
  )(value: Expr[Result[PathRef]])(
      ctx: Expr[mill.define.Ctx]
  ): Expr[Target[PathRef]] = {

    val expr = appImpl[Target, PathRef](
      (in, ev) => '{ new SourceImpl($ev, $ctx, ${ taskIsPrivate() }) },
      value,
      allowTaskReferences = false
    )
    Cacher.impl0(expr)

  }

  def inputImpl[T: Type](using
      Quotes
  )(value: Expr[Result[T]])(
      w: Expr[upickle.default.Writer[T]],
      ctx: Expr[mill.define.Ctx]
  ): Expr[Target[T]] = {

    val expr = appImpl[Target, T](
      (in, ev) => '{ new InputImpl[T]($ev, $ctx, $w, ${ taskIsPrivate() }) },
      value,
      allowTaskReferences = false
    )
    Cacher.impl0(expr)
  }

  def commandImpl[T: Type](using
      Quotes
  )(t: Expr[Result[T]])(
      w: Expr[W[T]],
      ctx: Expr[mill.define.Ctx],
      exclusive: Expr[Boolean]
  ): Expr[Command[T]] = {
    appImpl[Command, T](
      (in, ev) =>
        '{
          new Command[T]($in, $ev, $ctx, $w, ${ taskIsPrivate() }, exclusive = $exclusive)
        },
      t
    )
  }

  def workerImpl2[T: Type](using
      Quotes
  )(t: Expr[Result[T]])(
      ctx: Expr[mill.define.Ctx]
  ): Expr[Worker[T]] = {

    val expr = appImpl[Worker, T](
      (in, ev) => '{ new Worker[T]($in, $ev, $ctx, ${ taskIsPrivate() }) },
      t
    )
    Cacher.impl0(expr)
  }
}
