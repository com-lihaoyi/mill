package mill.api

import mill.api.BspServerHandle

private[mill] object BspContextApi {
  @volatile var bspServerHandle: BspServerHandle = null
}
