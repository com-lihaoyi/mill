package mill.api

import mill.api.Logger
import mill.api.Result
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.daemon.internal.{NamedTaskApi, TaskApi, TestReporter}
import mill.api.internal.Applicative.Applyable
import mill.api.internal.{Applicative, Cacher, NamedParameterOnlyDummy}
import upickle.ReadWriter
import upickle.Writer

import scala.annotation.{targetName, unused}
import scala.language.implicitConversions
import scala.quoted.*

/**
 * Models a single node in the Mill build graph, with a list of inputs and a
 * single output of type [[T]].
 *
 * Generally not instantiated manually, but instead constructed via the
 * [[Task.apply]] & similar macros.
 */
sealed abstract class Task[+T] extends Task.Ops[T] with Applyable[Task, T] with TaskApi[T] {

  /**
   * What other tasks does this task depend on?
   */
  val inputs: Seq[Task[?]]

  /**
   * Evaluate this task
   */
  def evaluate(args: mill.api.TaskCtx): Result[T]

  /**
   * Even if this task's inputs did not change, does it need to re-evaluate
   * anyway?
   */
  def sideHash: Int = 0

  /**
   * Whether or not this [[Task]] deletes the `Task.dest` folder between runs
   */
  def persistent: Boolean = false

  private[mill] def asSimple: Option[Task.Simple[T]] = None
  private[mill] def asCommand: Option[Task.Command[T]] = None
  private[mill] def asWorker: Option[Task.Worker[T]] = None
  private[mill] def isExclusiveCommand: Boolean = this match {
    case c: Task.Command[_] if c.exclusive => true
    case _ => false
  }
}

object Task {

  /**
   * Returns the [[mill.api.TaskCtx]] that is available within this task
   */
  def ctx()(using c: mill.api.TaskCtx): mill.api.TaskCtx = c

  /**
   * `Task.dest` is a unique `os.Path` (e.g. `out/classFiles.dest/` or `out/run.dest/`)
   * that is assigned to every Task or Command. It is cleared before your
   * task runs, and you can use it as a scratch space for temporary files or
   * a place to put returned artifacts. This is guaranteed to be unique for
   * every Task or Command, so you can be sure that you will not collide or
   * interfere with anyone else writing to those same paths.
   */
  def dest(using ctx: mill.api.TaskCtx.Dest): os.Path = ctx.dest

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
  def log(using ctx: mill.api.TaskCtx.Log): Logger = ctx.log

  /**
   * `Task.env` is the environment variable map passed to the Mill command when
   * it is run; typically used inside a `Task.Input` to ensure any changes in
   * the env vars are properly detected.
   *
   * Note that you should not use `sys.env`, as Mill's long-lived server
   * process means that `sys.env` variables may not be up to date.
   */
  def env(using ctx: mill.api.TaskCtx.Env): Map[String, String] = ctx.env

  /**
   * Returns the implicit [[mill.api.TaskCtx.Args.args]] in scope.
   */
  def args(using ctx: mill.api.TaskCtx.Args): IndexedSeq[?] = ctx.args

  /**
   * Report test results to BSP for IDE integration
   */
  def testReporter(using ctx: mill.api.TaskCtx): TestReporter = ctx.testReporter

  /**
   * Report build results to BSP for IDE integration
   */
  def reporter(using ctx: mill.api.TaskCtx): Int => Option[CompileProblemReporter] =
    ctx.reporter

  /**
   * Provides the `.fork.async` and `.fork.await` APIs for spawning and joining
   * async futures within your task in a Mill-friendly mannter
   */
  def fork(using ctx: mill.api.TaskCtx): mill.api.TaskCtx.Fork.Api = ctx.fork

  def offline(using ctx: mill.api.TaskCtx): Boolean = ctx.offline

  def fail(msg: String)(using ctx: mill.api.TaskCtx): Nothing = ctx.fail(msg)

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

  /**
   * A specialization of [[Input]] defined via `Task.Sources`, [[Sources]]
   * uses [[PathRef]]s to compute a signature for a set of source files and
   * folders.
   *
   * This is most used when detecting changes in source code: when you edit a
   * file and run `mill compile`, it is the `Task.Sources` that re-computes the
   * signature for you source files/folders and decides whether or not downstream
   * [[Task.Computed]]s need to be invalidated and re-computed.
   */
  inline def Sources(inline values: (os.SubPath | os.FilePath)*)(using
      inline ctx: mill.api.ModuleCtx
  ): Simple[Seq[PathRef]] = ${
    Macros.sourcesImpl('{ values.map(p => PathRef(mapToPath(p))) })('ctx)
  }

  inline private def mapToPath(value: os.SubPath | os.FilePath)(using
      inline ctx: mill.api.ModuleCtx
  ): os.Path = value match {
    // TODO: support "."
//    case str: String => ctx.millSourcePath / os.up / os.PathChunk.segmentsFromString(str)
    case sub: os.SubPath => ctx.millSourcePath / os.up / os.PathChunk.SubPathChunk(sub)
    case rel: os.RelPath => ctx.millSourcePath / os.up / os.PathChunk.RelPathChunk(rel)
    case p: os.Path => p
  }

  /**
   * Similar to [[Sources]], but only for a single source file or folder. Defined
   * using `Task.Source`.
   */
  inline def Source(inline value: os.SubPath | os.FilePath)(using
      inline ctx: mill.api.ModuleCtx
  ): Simple[PathRef] =
    ${ Macros.sourceImpl('{ PathRef(mapToPath(value)) })('ctx) }

  /**
   * [[Input]]s, normally defined using `Task.Input`, are [[Task.Named]]s that
   * re-evaluate every time Mill is run. This is in contrast to [[Task.Computed]]s
   * which only re-evaluate when upstream tasks change.
   *
   * [[Input]]s are useful when you want to capture some input to the Mill
   * build graph that comes from outside: maybe from an environment variable, a
   * JVM system property, the hash returned by `git rev-parse HEAD`. Reading
   * these external mutable variables inside a `Task{...}` [[Task.Computed]] will
   * incorrectly cache them forever. Reading them inside a `Task.Input{...}`
   * will re-compute them every time, and only if the value changes would it
   * continue to invalidate downstream [[Task.Computed]]s
   *
   * The most common case of [[Input]] is [[Source]] and [[Sources]],
   * used for detecting changes to source files.
   */
  inline def Input[T](inline value: Result[T])(using
      inline w: Writer[T],
      inline ctx: ModuleCtx
  ): Simple[T] =
    ${ Macros.inputImpl[T]('value)('w, 'ctx) }

  /**
   * [[Command]]s are only [[Task.Named]]s defined using
   * `def foo() = Task.Command{...}` and are typically called from the
   * command-line. Unlike other [[Task.Named]]s, [[Command]]s can be defined to
   * take arguments that are automatically converted to command-line
   * arguments, as long as an implicit [[mainargs.TokensReader]] is available.
   */
  inline def Command[T](inline t: Result[T])(using
      inline w: Writer[T],
      inline ctx: ModuleCtx
  ): Command[T] =
    ${ Macros.commandImpl[T]('t)('w, 'ctx, exclusive = '{ false }, persistent = '{ false }) }

  /**
   * @param exclusive Exclusive commands run serially at the end of an evaluation,
   *                  without any other tasks running parallel, and without the
   *                  terminal logging prefixes that are applied to normal tasks.
   *                  These are normally used for "top level" commands which are
   *                  run directly to perform some action or display some output
   *                  to the user.
   * @param persistent If true the `Task.dest` directory is not cleaned between
   *                   runs.
   */
  def Command(
      @unused t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      exclusive: Boolean = false,
      persistent: Boolean = false
  ): CommandFactory = new CommandFactory(exclusive = exclusive, persistent = persistent)
  class CommandFactory private[mill] (val exclusive: Boolean, val persistent: Boolean) {
    inline def apply[T](inline t: Result[T])(using
        inline w: Writer[T],
        inline ctx: ModuleCtx
    ): Command[T] =
      ${ Macros.commandImpl[T]('t)('w, 'ctx, '{ this.exclusive }, '{ this.persistent }) }
  }

  /**
   * [[Worker]] is a [[Task.Named]] that lives entirely in-memory, defined using
   * `Task.Worker{...}`. The value returned by `Task.Worker{...}` is long-lived,
   * persisting as long as the Mill process is kept alive (e.g. via `--watch`,
   * or via its default `MillDaemonMain` server process). This allows the user to
   * perform in-memory caching that is even more aggressive than the disk-based
   * caching enabled by [[PersistentImpl]]: your [[Worker]] can cache running
   * sub-processes, JVM Classloaders with JITed code, and all sorts of things
   * that do not easily serialize to JSON on disk.
   *
   * Like [[PersistentImpl]], The user defining a [[Worker]] assumes the
   * responsibility of ensuring the implementation is idempotent regardless of
   * what in-memory state the worker may have.
   */
  inline def Worker[T](inline t: Result[T])(using inline ctx: mill.api.ModuleCtx): Worker[T] =
    ${ Macros.workerImpl2[T]('t)('ctx) }

  /**
   * Creates an anonymous `Task`. These depend on other tasks and
   * be-depended-upon by other tasks, but cannot be run directly from the
   * command line and do not perform any caching. Typically used as helpers to
   * implement `Task{...}` tasks.
   */
  inline def Anon[T](inline t: Result[T])(using
      inline enclosing: sourcecode.Enclosing
  ): Task[T] =
    ${ Macros.anonTaskImpl[T]('t, 'enclosing) }

  inline def apply[T](inline t: Result[T])(using
      inline rw: ReadWriter[T],
      inline ctx: mill.api.ModuleCtx
  ): Simple[T] =
    ${ Macros.taskResultImpl[T]('t)('rw, 'ctx, '{ false }) }

  // Overload of [[apply]] to improve type inference for `Task{ Nil }` and `Task { Seq() }`
  @targetName("applySeq")
  inline def apply[T](inline t: Result[Seq[T]])(using
      inline rw: ReadWriter[Seq[T]],
      inline ctx: mill.api.ModuleCtx
  ): Simple[Seq[T]] = ${
    Macros.taskResultImpl[Seq[T]]('t)('rw, 'ctx, '{ false })
  }

  /**
   * Persistent tasks are defined using
   * the `Task(persistent = true){...}` syntax. The main difference is that while
   * [[Task.Computed]] deletes the `Task.dest` folder in between runs,
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
      @unused t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      persistent: Boolean = false
  ): ApplyFactory = new ApplyFactory(persistent)
  class ApplyFactory private[mill] (val persistent: Boolean) {
    inline def apply[T](inline t: Result[T])(using
        inline rw: ReadWriter[T],
        inline ctx: ModuleCtx
    ): Simple[T] = ${ Macros.taskResultImpl[T]('t)('rw, 'ctx, '{ persistent }) }
  }

  // The extra `(x: T) = null` parameter list is necessary to make type inference work
  // right, ensuring that `T` is fully inferred before implicit resolution starts
  def Literal[T](s: String)(using
      x: T = null.asInstanceOf[T]
  )(using li: LiteralImplicit[T]): Task.Simple[T] = {
    assert(li.ctx != null, "Unable to resolve context")
    assert(li.writer != null, "Unable to resolve JSON writer")
    assert(li.reader != null, "Unable to resolve JSON reader")
    new Task.Input[T](
      (_, _) =>
        PathRef
          .currentOverrideModulePath
          .withValue(li.ctx.enclosingModule.moduleCtx.millSourcePath) {
            Result.Success(upickle.default.read[T](s)(using li.reader))
          },
      li.ctx,
      li.writer,
      None
    )
  }

  class LiteralImplicit[T](
      val reader: upickle.default.Reader[T],
      val writer: upickle.default.Writer[T],
      val ctx: ModuleCtx
  )
  object LiteralImplicit {
    // Use a custom macro to perform the implicit lookup so we have more control over implicit
    // resolution failures. In this case, we want to fall back to `null` if an implicit search
    // fails so we can provide a good error message
    implicit inline def create[T]: LiteralImplicit[T] = ${ createImpl[T] }

    private def createImpl[T: Type](using Quotes): Expr[LiteralImplicit[T]] = {
      import quotes.reflect.*

      def summonOrNull[U: Type]: Expr[U] = {
        Implicits.search(TypeRepr.of[U]) match {
          case s: ImplicitSearchSuccess => s.tree.asExprOf[U] // Use the found given
          case _: ImplicitSearchFailure =>
            '{ null.asInstanceOf[U] } // Includes both NoMatchingImplicits and AmbiguousImplicits
        }
      }

      val readerExpr = summonOrNull[upickle.default.Reader[T]]
      val writerExpr = summonOrNull[upickle.default.Writer[T]]
      val ctxExpr = summonOrNull[ModuleCtx]

      '{ new LiteralImplicit[T]($readerExpr, $writerExpr, $ctxExpr) }
    }
  }

  abstract class Ops[+T] { this: Task[T] =>
    def map[V](f: T => V): Task[V] = new Task.Mapped(this, f)
    def filter(f: T => Boolean): Task[T] = this
    def withFilter(f: T => Boolean): Task[T] = this
    def zip[V](other: Task[V]): Task[(T, V)] = new Task.Zipped(this, other)

  }

  private[api] class Sequence[+T](inputs0: Seq[Task[T]]) extends Task[Seq[T]] {
    val inputs: Seq[Task[?]] = inputs0
    def evaluate(ctx: mill.api.TaskCtx): Result[Seq[T]] = {
      for (i <- 0 until ctx.args.length)
        yield ctx.args(i).asInstanceOf[T]
    }
  }

  private[api] class Mapped[+T, +V](source: Task[T], f: T => V) extends Task[V] {
    def evaluate(ctx: mill.api.TaskCtx): Result[V] = f(ctx.arg(0))
    val inputs: Seq[Task[?]] = List(source)
  }

  private[api] class Zipped[+T, +V](source1: Task[T], source2: Task[V]) extends Task[(T, V)] {
    def evaluate(ctx: mill.api.TaskCtx): Result[(T, V)] = (ctx.arg(0), ctx.arg(1))
    val inputs: Seq[Task[?]] = List(source1, source2)
  }

  /**
   * Represents a task that can be referenced by its path segments. `Task{...}`
   * tasks, `Task.Input`, `Task.Worker`, etc. but not including anonymous
   * `Task.Anon` or `Task.traverse` etc. instances
   */
  trait Named[+T] extends Task[T] with NamedTaskApi[T] {

    def ctx0: mill.api.ModuleCtx

    def isPrivate: Option[Boolean]

    def label: String = ctx.segments.value.last match {
      case Segment.Label(v) => v
      case Segment.Cross(_) => throw new IllegalArgumentException(
          "Task.Named only support a ctx with a Label segment, but found a Cross."
        )
    }

    override def toString = ctx.segments.render

    def evaluate(ctx: mill.api.TaskCtx): Result[T] = evaluate0(ctx.args, ctx)

    def evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[T]

    val ctx: ModuleCtx = ctx0

    def readWriterOpt: Option[upickle.ReadWriter[?]] = None

    def writerOpt: Option[upickle.Writer[?]] = readWriterOpt.orElse(None)
  }

  class Computed[+T](
      val inputs: Seq[Task[Any]],
      val evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[T],
      val ctx0: mill.api.ModuleCtx,
      val readWriter: ReadWriter[?],
      val isPrivate: Option[Boolean],
      override val persistent: Boolean
  ) extends Simple[T] {
    override def asSimple: Option[Simple[T]] = Some(this)

    // FIXME: deprecated return type: Change to Option
    override def readWriterOpt: Some[ReadWriter[?]] = Some(readWriter)
  }

  /**
   * A Simple Task is a [[Task.Named]] that is cached on disk; either a
   * [[Task.Computed]] or an [[Input]]
   */
  trait Simple[+T] extends Task.Named[T]

  object Simple {

    /**
     * A simple task is the most common [[Task]] a user would encounter, commonly
     * defined using the `def foo = Task {...}` syntax. [[Task.Computed]]s require that their
     * return type is JSON serializable. In return they automatically caches their
     * return value to disk, only re-computing if upstream [[Task]]s change
     */
    implicit inline def create[T](inline t: T)(using
        inline rw: ReadWriter[T],
        inline ctx: ModuleCtx
    ): Simple[T] =
      ${ Macros.taskResultImpl[T]('{ Result.Success(t) })('rw, 'ctx, '{ false }) }

    // Overload of [[create]] specialized to working on `Seq`s, to improve the type
    // inference for `Task{ Nil }` or `Task{ Seq() }`
    implicit inline def createSeq[T](inline t: Seq[T])(using
        inline rw: ReadWriter[Seq[T]],
        inline ctx: ModuleCtx
    ): Simple[Seq[T]] =
      ${ Macros.taskResultImpl[Seq[T]]('{ Result.Success(t) })('rw, 'ctx, '{ false }) }

    implicit inline def create[T](inline t: Result[T])(using
        inline rw: ReadWriter[T],
        inline ctx: ModuleCtx
    ): Simple[T] =
      ${ Macros.taskResultImpl[T]('t)('rw, 'ctx, '{ false }) }

  }

  class Anon[T](
      val inputs: Seq[Task[?]],
      evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[T],
      enclosing: sourcecode.Enclosing
  ) extends Task[T] {
    def evaluate(ctx: mill.api.TaskCtx) = evaluate0(ctx.args, ctx)

    override def toString =
      s"Task.Anon@${System.identityHashCode(this).toHexString}(${enclosing.value})"
  }

  class Command[+T](
      val inputs: Seq[Task[Any]],
      val evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[T],
      val ctx0: mill.api.ModuleCtx,
      val writer: Writer[?],
      val isPrivate: Option[Boolean],
      val exclusive: Boolean,
      override val persistent: Boolean
  ) extends Task.Named[T] {

    override def asCommand: Some[Command[T]] = Some(this)
    // FIXME: deprecated return type: Change to Option
    override def writerOpt: Some[Writer[?]] = Some(writer)
  }

  class Worker[+T](
      val inputs: Seq[Task[Any]],
      val evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[T],
      val ctx0: mill.api.ModuleCtx,
      val isPrivate: Option[Boolean]
  ) extends Task.Named[T] {
    override def persistent = false
    override def asWorker: Some[Worker[T]] = Some(this)
  }

  class Input[T](
      val evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[T],
      val ctx0: mill.api.ModuleCtx,
      val writer: upickle.Writer[?],
      val isPrivate: Option[Boolean]
  ) extends Simple[T] {
    val inputs = Nil
    override def sideHash: Int = util.Random.nextInt()
    // FIXME: deprecated return type: Change to Option
    override def writerOpt: Some[Writer[?]] = Some(writer)
  }

  class Sources(
      evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[Seq[PathRef]],
      ctx0: mill.api.ModuleCtx,
      isPrivate: Option[Boolean]
  ) extends Input[Seq[PathRef]](
        evaluate0,
        ctx0,
        upickle.readwriter[Seq[PathRef]],
        isPrivate
      ) {
    override def readWriterOpt = Some(upickle.readwriter[Seq[PathRef]])
  }

  class Source(
      evaluate0: (Seq[Any], mill.api.TaskCtx) => Result[PathRef],
      ctx0: mill.api.ModuleCtx,
      isPrivate: Option[Boolean]
  ) extends Input[PathRef](
        evaluate0,
        ctx0,
        upickle.readwriter[PathRef],
        isPrivate
      ) {
    override def readWriterOpt = Some(upickle.readwriter[PathRef])
  }

  private object Macros {
    def appImpl[M[_]: Type, T: Type](using
        Quotes
    )(
        traverseCtx: (
            Expr[Seq[Task[Any]]],
            Expr[(Seq[Any], mill.api.TaskCtx) => Result[T]]
        ) => Expr[M[T]],
        t: Expr[Result[T]],
        allowTaskReferences: Boolean = true
    ): Expr[M[T]] =
      Applicative.impl[M, Task, Result, T, mill.api.TaskCtx](traverseCtx, t, allowTaskReferences)

    private def taskIsPrivate()(using Quotes): Expr[Option[Boolean]] =
      Cacher.withMacroOwner {
        owner =>
          import quotes.reflect.*
          if owner.flags.is(Flags.Private) then Expr(Some(true))
          else Expr(Some(false))
      }

    def anonTaskImpl[T: Type](t: Expr[Result[T]], enclosing: Expr[sourcecode.Enclosing])(using
        Quotes
    ): Expr[Task[T]] = {
      appImpl[Task, T]((in, ev) => '{ new Anon($in, $ev, $enclosing) }, t)
    }

    def taskResultImpl[T: Type](using
        Quotes
    )(t: Expr[Result[T]])(
        rw: Expr[ReadWriter[T]],
        ctx: Expr[mill.api.ModuleCtx],
        persistent: Expr[Boolean]
    ): Expr[Simple[T]] = {
      assertTaskShapeOwner("Task", 0)
      val expr = appImpl[Simple, T](
        (in, ev) =>
          '{ new Task.Computed[T]($in, $ev, $ctx, $rw, ${ taskIsPrivate() }, $persistent) },
        t
      )

      Cacher.impl0(expr)
    }

    def sourcesImpl(using
        Quotes
    )(
        values: Expr[Result[Seq[PathRef]]]
    )(
        ctx: Expr[mill.api.ModuleCtx]
    ): Expr[Simple[Seq[PathRef]]] = {
      assertTaskShapeOwner("Task.Sources", 0)
      val expr = appImpl[Simple, Seq[PathRef]](
        ( /*in*/ _, ev) => '{ new Sources($ev, $ctx, ${ taskIsPrivate() }) },
        values,
        allowTaskReferences = false
      )
      Cacher.impl0(expr)
    }

    def assertTaskShapeOwner(using
        quotes: Quotes
    )(typeName: String, expectedParamListCount: Int) = {
      import quotes.reflect.*
      val owner = sourcecode.Macros.actualOwner(Symbol.spliceOwner)
      owner match {
        case defSym
            if defSym.isDefDef
              && defSym.tree.asInstanceOf[DefDef].paramss.length == expectedParamListCount =>
        case _ =>
          val plural = if (expectedParamListCount == 1) "" else "s"
          val err =
            s"`$typeName` definition `$owner` must have $expectedParamListCount parameter list$plural"
          owner.pos match {
            case Some(p) => report.error(err, p)
            case None => report.error(err)
          }
      }
    }
    def sourceImpl(using
        Quotes
    )(value: Expr[Result[PathRef]])(
        ctx: Expr[mill.api.ModuleCtx]
    ): Expr[Simple[PathRef]] = {
      assertTaskShapeOwner("Task.Source", 0)
      val expr = appImpl[Simple, PathRef](
        ( /*in*/ _, ev) => '{ new Source($ev, $ctx, ${ taskIsPrivate() }) },
        value,
        allowTaskReferences = false
      )
      Cacher.impl0(expr)

    }

    def inputImpl[T: Type](using
        Quotes
    )(value: Expr[Result[T]])(
        w: Expr[upickle.Writer[T]],
        ctx: Expr[mill.api.ModuleCtx]
    ): Expr[Simple[T]] = {
      assertTaskShapeOwner("Task.Input", 0)
      val expr = appImpl[Simple, T](
        ( /*in*/ _, ev) => '{ new Input[T]($ev, $ctx, $w, ${ taskIsPrivate() }) },
        value,
        allowTaskReferences = false
      )
      Cacher.impl0(expr)
    }

    def commandImpl[T: Type](using
        Quotes
    )(t: Expr[Result[T]])(
        w: Expr[Writer[T]],
        ctx: Expr[mill.api.ModuleCtx],
        exclusive: Expr[Boolean],
        persistent: Expr[Boolean]
    ): Expr[Command[T]] = {
      assertTaskShapeOwner("Task.Command", 1)
      appImpl[Command, T](
        (in, ev) =>
          '{
            new Command[T](
              $in,
              $ev,
              $ctx,
              $w,
              ${ taskIsPrivate() },
              exclusive = $exclusive,
              persistent = $persistent
            )
          },
        t
      )
    }

    def workerImpl2[T: Type](using
        Quotes
    )(t: Expr[Result[T]])(
        ctx: Expr[mill.api.ModuleCtx]
    ): Expr[Worker[T]] = {
      assertTaskShapeOwner("Task.Worker", 0)
      val expr = appImpl[Worker, T](
        (in, ev) => '{ new Worker[T]($in, $ev, $ctx, ${ taskIsPrivate() }) },
        t
      )
      Cacher.impl0(expr)
    }
  }
}
