package mill.api

case class JvmBuildTarget(
    javaHome: Option[BspUri],
    javaVersion: Option[String]
)

object JvmBuildTarget {
  val dataKind: String = "jvm"
}
