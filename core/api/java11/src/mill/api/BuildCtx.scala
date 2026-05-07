package mill.api
import collection.mutable
import mill.api.daemon.Watchable
import mill.constants.EnvVars
import scala.util.DynamicVariable

/**
 * BuildCtx contains APIs that are global throughout an entire build, without
 * being tied to any particular task or module
 */
object BuildCtx {

  /**
   * This is the `os.Path` pointing to the project root directory.
   *
   * This is the preferred access to the project directory, and should
   * always be preferred over `os.pwd`* (which might also point to the
   * project directory in classic cli scenarios, but might not in other
   * use cases like BSP or LSP server usage).
   */
  def workspaceRoot: os.Path = workspaceRoot0.value

  private[mill] val workspaceRoot0: scala.util.DynamicVariable[os.Path] =
    DynamicVariable(sys.env.get(EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_, os.pwd)))

  /**
   * Global repositories configured via `mill-repositories` in the build file header or
   * `.mill-repositories` config file. These are used for resolving Mill's own dependencies
   * (daemon jars, JVM index) and as default repositories for `CoursierModule`.
   */
  def millRepositories: Seq[String] = mill.api.daemon.MillRepositories.get

  /**
   * Disable Mill's filesystem read/write checker, which normally enforces best practices
   * about what code or tasks are able to read and write to what locations on disk.
   */
  def withFilesystemCheckerDisabled[T](block: => T): T =
    os.checker.withValue(os.Checker.Nop) { block }

  // Module-init watches: populated by `<clinit>` of the build classloader
  // (serialized by the JVM), so a shared default buffer is safe.
  private val moduleWatchedDefault: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]

  // Eval watches: populated per evaluation via `BuildCtx.evalWatch`.
  // The DynamicVariable + `ExecutionContexts.ThreadPool` capture/rebind
  // give each evaluation an isolated buffer so concurrent launchers
  // don't clobber each other's watches.
  private[mill] val watchedValues0: DynamicVariable[mutable.Buffer[Watchable]] =
    DynamicVariable(moduleWatchedDefault)
  private[mill] val evalWatchedValues0: DynamicVariable[mutable.Buffer[Watchable]] =
    DynamicVariable(mutable.Buffer.empty[Watchable])

  protected[mill] def watchedValues: mutable.Buffer[Watchable] = watchedValues0.value
  protected[mill] def evalWatchedValues: mutable.Buffer[Watchable] = evalWatchedValues0.value

  /**
   * Run `body` with a fresh per-evaluation `evalWatchedValues` buffer; returns
   * the buffer alongside the body result so the caller can collect the
   * accumulated watches without racing other evaluations.
   */
  private[mill] def withEvalWatchedValues[T](body: => T): (T, mutable.Buffer[Watchable]) = {
    val buf = mutable.Buffer.empty[Watchable]
    val result = evalWatchedValues0.withValue(buf)(body)
    (result, buf)
  }

  /**
   * Register a compute value as watched during module initialization, so Mill knows
   * that if the value changes during `--watch` the module needs to be re-instantiated.
   */
  def watchValue[T](v0: => T)(using fn: sourcecode.FileName, ln: sourcecode.Line): T = {
    withFilesystemCheckerDisabled {
      val v = v0
      val watchable = Watchable.Value(
        () => v0.hashCode,
        v.hashCode(),
        fn.value + ":" + ln.value
      )
      watchedValues0.value.append(watchable)
      v
    }
  }

  def watch(p: os.Path): os.Path = withFilesystemCheckerDisabled {
    val watchable = Watchable.Path.from(PathRef(p))
    watchedValues0.value.append(watchable)
    p
  }
  def evalWatch(p: os.Path): os.Path = withFilesystemCheckerDisabled {
    val watchable = Watchable.Path.from(PathRef(p))
    evalWatchedValues0.value.append(watchable)
    p
  }

  def watch0(w: Watchable): Unit = watchedValues0.value.append(w)

  def evalWatch0(w: Watchable): Unit = evalWatchedValues0.value.append(w)
}
