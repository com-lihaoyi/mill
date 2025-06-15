package mill.define
import collection.mutable
import mill.api.Watchable
import mill.constants.EnvVars
object BuildCtx {

  /**
   * This is the `os.Path` pointing to the project root directory.
   *
   * This is the preferred access to the project directory, and should
   * always be prefered over `os.pwd`* (which might also point to the
   * project directory in classic cli scenarios, but might not in other
   * use cases like BSP or LSP server usage).
   */
  def workspaceRoot: os.Path = os.Path(mill.api.WorkspaceRoot.workspaceRoot0.value)


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
  def watchValue[T](v0: => T)(implicit fn: sourcecode.FileName, ln: sourcecode.Line): T = {
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
