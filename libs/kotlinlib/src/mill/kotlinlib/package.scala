package mill

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
