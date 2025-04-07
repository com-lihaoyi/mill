package mill.runner.api
case class ScalaBuildTarget(
    /** The Scala organization that is used for a target. */
    scalaOrganization: String,
    /** The scala version to compile this target */
    scalaVersion: String,
    /**
     * The binary version of scalaVersion.
     * For example, 2.12 if scalaVersion is 2.12.4.
     */
    scalaBinaryVersion: String,
    /** The target platform for this target */
    platform: ScalaPlatform,
    /** A sequence of Scala jars such as scala-library, scala-compiler and scala-reflect. */
    jars: Seq[String],
    /** The jvm build target describing jdk to be used */
    jvmBuildTarget: Option[JvmBuildTarget]
)

object ScalaBuildTarget {
  val dataKind: String = "scala"
}
