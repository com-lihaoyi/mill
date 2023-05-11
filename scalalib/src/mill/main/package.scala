package mill

package object main {
  @deprecated("use mill.util.Jvm")
  val Jvm = mill.util.Jvm

  @deprecated("use mill.util.Util")
  val Util = mill.util.Util

  @deprecated("use mill.util.CoursierSupport")
  val CoursierSupport = mill.util.CoursierSupport

  @deprecated("use mill.scalalib.Assembly")
  val Assembly = mill.scalalib.Assembly
}
