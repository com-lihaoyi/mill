package mill

package object javalib extends mill.scalalib.JsonFormatters {
  implicit class DepSyntax(ctx: StringContext) {
    def ivy(args: Any*): Dep = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }
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

  type CrossVersion = mill.scalalib.CrossVersion
  val CrossVersion = mill.scalalib.CrossVersion
}
