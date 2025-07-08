package mill

/**
 * Kotlin toolchain containing [[KotlinModule]] and other functionality related to building
 * Kotlin projects. Also supports [[js.KotlinJsModule]] for building Kotlin-JS
 * projects that run in the browser. The toolchain for building Kotlin on android
 * lives separately in `mill.androidlib`.
 */
package object kotlinlib {
  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = mill.javalib.DepSyntax(ctx).mvn(args*)

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mill.javalib.DepSyntax(ctx).mvn(args*)
  }

  type Dep = mill.javalib.Dep
  val Dep = mill.javalib.Dep

  type TestModule = mill.javalib.TestModule
  val TestModule = mill.javalib.TestModule

  type PublishModule = mill.javalib.PublishModule
  val PublishModule = mill.javalib.PublishModule

  type NativeImageModule = mill.javalib.NativeImageModule

  type JvmWorkerModule = mill.javalib.JvmWorkerModule
  val JvmWorkerModule = mill.javalib.JvmWorkerModule
}
