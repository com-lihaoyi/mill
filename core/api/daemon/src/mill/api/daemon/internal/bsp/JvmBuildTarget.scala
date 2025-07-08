package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.bsp.BspUri

case class JvmBuildTarget(
    javaHome: Option[BspUri],
    javaVersion: Option[String]
)

object JvmBuildTarget {
  val dataKind: String = "jvm"
}
