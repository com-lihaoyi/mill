package mill

package object javalib extends mill.scalalib.JsonFormatters {
  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = mill.scalalib.DepSyntax(ctx).mvn(args*)

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mill.scalalib.DepSyntax(ctx).mvn(args*)
  }

  val Assembly = mill.scalalib.Assembly
  type Assembly = mill.scalalib.Assembly

  type JavaModule = mill.scalalib.JavaModule

  type NativeImageModule = mill.scalalib.NativeImageModule

  val JvmWorkerModule = mill.scalalib.JvmWorkerModule
  type JvmWorkerModule = mill.scalalib.JvmWorkerModule

  type CoursierModule = mill.scalalib.CoursierModule

  type JsonFormatters = mill.scalalib.JsonFormatters
  val JsonFormatters = mill.scalalib.JsonFormatters

  val Lib = mill.scalalib.Lib

  type RunModule = mill.scalalib.RunModule

  type TestModule = mill.scalalib.TestModule
  val TestModule = mill.scalalib.TestModule

  type MavenModule = mill.scalalib.MavenModule

  type PublishModule = mill.scalalib.PublishModule
  val PublishModule = mill.scalalib.PublishModule

  type Dep = mill.scalalib.Dep
  val Dep = mill.scalalib.Dep

  type BoundDep = mill.scalalib.BoundDep
  val BoundDep = mill.scalalib.BoundDep

  type CrossVersion = mill.define.CrossVersion
  val CrossVersion = mill.define.CrossVersion
}
