package mill.bsp

import os.SubPath
object Constants {
  val bspDir: SubPath = os.sub / ".bsp"
  val bspProtocolVersion = BuildInfo.bsp4jVersion
  val bspWorkerImplClass = "mill.bsp.worker.BspWorkerImpl"
  val bspWorkerBuildInfoClass = "mill.bsp.worker.BuildInfo"
  val languages: Seq[String] = Seq("java", "scala", "kotlin")
  val serverName = "mill-bsp"
}
