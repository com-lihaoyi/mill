package mill.api.internal

import mill.api.Watchable

trait BuildFileApi {
  def rootModule: RootModuleApi
  def moduleWatchedValues: Seq[Watchable]
  def evalWatchedValues: collection.mutable.Buffer[Watchable]
}
object BuildFileApi {
  class Bootstrap(val rootModule: RootModuleApi) extends BuildFileApi {
    def moduleWatchedValues = Nil
    def evalWatchedValues = collection.mutable.Buffer[Watchable]()
  }
}
