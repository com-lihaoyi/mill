package mill

/**
 * Kotlin toolchain containing [[KotlinModule]] and other functionality related to building
 * Kotlin projects. Also supports [[js.KotlinJsModule]] for building Kotlin-JS
 * projects that run in the browser. The toolchain for building Kotlin on android
 * lives separately in `mill.androidlib`.
 */
package object kotlinlib {
  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = mill.scalalib.DepSyntax(ctx).mvn(args*)

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mill.scalalib.DepSyntax(ctx).mvn(args*)
  }

  type Dep = mill.scalalib.Dep
  val Dep = mill.scalalib.Dep

  type TestModule = mill.scalalib.TestModule
  val TestModule = mill.scalalib.TestModule

  type PublishModule = mill.scalalib.PublishModule
  val PublishModule = mill.scalalib.PublishModule

  type NativeImageModule = mill.scalalib.NativeImageModule

  type JvmWorkerModule = mill.scalalib.JvmWorkerModule
  val JvmWorkerModule = mill.scalalib.JvmWorkerModule
}
