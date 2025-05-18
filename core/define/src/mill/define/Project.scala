package mill.define
import collection.mutable
import mill.api.Watchable
import mill.constants.EnvVars
object Project {
  val workspaceRoot: os.Path =
    sys.env.get(EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_, os.pwd))

  def withFilesystemCheckerDisabled[T](block: => T): T =
    os.checker.withValue(os.Checker.Nop) { block }

  protected[mill] val watchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]
  protected[mill] val evalWatchedValues: mutable.Buffer[Watchable] = mutable.Buffer.empty[Watchable]
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
