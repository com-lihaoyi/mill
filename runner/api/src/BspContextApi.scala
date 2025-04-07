package mill.runner.api

private[mill] object BspContextApi {
  @volatile var bspServerHandle: BspServerHandle = null
}
