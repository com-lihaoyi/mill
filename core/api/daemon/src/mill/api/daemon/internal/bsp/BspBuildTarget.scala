package mill.api.daemon.internal.bsp

case class BspBuildTarget(
    displayName: Option[String],
    baseDirectory: Option[java.nio.file.Path],
    tags: Seq[String],
    languageIds: Seq[String],
    canCompile: Boolean,
    canTest: Boolean,
    canRun: Boolean,
    canDebug: Boolean
)
