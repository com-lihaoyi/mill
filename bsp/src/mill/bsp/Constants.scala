package mill.bsp

import mill.api.internal

@internal
object Constants {
  val bspDir = os.sub / ".bsp"
  val bspProtocolVersion = BuildInfo.bsp4jVersion
  val bspWorkerImplClass = "mill.bsp.worker.BspWorkerImpl"
  val bspWorkerBuildInfoClass = "mill.bsp.worker.BuildInfo"
  val languages = Seq("scala", "java")
  val serverName = "mill-bsp"
}
