package mill.api.internal

private[mill] object BspContextApi {
  @volatile var bspServerHandle: BspServerHandle = null
}
