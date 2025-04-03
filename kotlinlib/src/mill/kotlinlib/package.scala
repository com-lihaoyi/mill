package mill

package object kotlinlib {
  implicit class DepSyntax(ctx: StringContext) {
    def ivy(args: Any*): Dep = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }
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
