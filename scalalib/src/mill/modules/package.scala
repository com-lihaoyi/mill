package mill

package object modules {
  @deprecated("use mill.util.Jvm", "Mill 0.11.0-M9")
  val Jvm = mill.util.Jvm

  @deprecated("use mill.util.Util", "Mill 0.11.0-M9")
  val Util = mill.util.Util

  @deprecated("use mill.util.CoursierSupport", "Mill 0.11.0-M9")
  val CoursierSupport = mill.util.CoursierSupport

  @deprecated("use mill.scalalib.Assembly", "Mill 0.11.0-M9")
  val Assembly = mill.scalalib.Assembly
}
