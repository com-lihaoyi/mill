package mill.api
import collection.mutable
import mill.api.Watchable
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
   * Disable Mill's filesystem read/write checker, which normally enforces best practices
   * about what code or tasks are able to read and write to what locations on disk.
   */
  def withFilesystemCheckerDisabled[T](block: => T): T =
    os.checker.withValue(os.Checker.Nop) { block }

  protected[mill] val watchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]
  protected[mill] val evalWatchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]

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
      watchedValues.append(watchable)
      v
    }
  }

  def watch(p: os.Path): os.Path = {
    val watchable = Watchable.Path(p.toNIO, false, PathRef(p).sig)
    watchedValues.append(watchable)
    p
  }

  def watch0(w: Watchable): Unit = watchedValues.append(w)

  def evalWatch0(w: Watchable): Unit = evalWatchedValues.append(w)
}
