package mill

/**
 * Java toolchain containing [[JavaModule]] and other functionality related to building
 * Java projects. The toolchain for building Java on android
 * lives separately in `mill.androidlib`.
 */
package object javalib extends mill.scalalib.JsonFormatters {
  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = mill.scalalib.DepSyntax(ctx).mvn(args*)

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mill.scalalib.DepSyntax(ctx).mvn(args*)
  }
}
