package mill.scalalib.bsp

case class BspBuildTarget(
    displayName: Option[String],
    baseDirectory: Option[os.Path],
    tags: Seq[String],
    languageIds: Seq[String],
    canCompile: Boolean,
    canTest: Boolean,
    canRun: Boolean,
    canDebug: Boolean
)
