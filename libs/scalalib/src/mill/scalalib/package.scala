package mill

/**
 * Scala toolchain containing [[ScalaModule]] and other functionality related to building
 * Scala projects on the JVM. Scala.js and Scala-Native toolchains are in `mill.scalajslib`
 * and `mill.scalanativelib` respectively.
 */
package object scalalib extends mill.scalalib.JsonFormatters {

  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = mill.javalib.DepSyntax(ctx).mvn(args*)

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mill.javalib.DepSyntax(ctx).mvn(args*)
  }

}
