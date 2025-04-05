package mill.runner.api
import collection.mutable
trait RootModuleApi {
  protected[mill] def watchedValues: mutable.Buffer[Watchable]
  protected[mill] def evalWatchedValues: mutable.Buffer[Watchable]
}
