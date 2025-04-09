package mill.api.bsp

import mill.api.Watchable

import scala.collection.mutable
trait RootModuleApi {
  protected[mill] def watchedValues: mutable.Buffer[Watchable]
  protected[mill] def evalWatchedValues: mutable.Buffer[Watchable]
}
