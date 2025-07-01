package mill.api.shared.internal.bsp

import mill.api.shared.internal.bsp.BspUri

case class JvmBuildTarget(
    javaHome: Option[BspUri],
    javaVersion: Option[String]
)

object JvmBuildTarget {
  val dataKind: String = "jvm"
}
