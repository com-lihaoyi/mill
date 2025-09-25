package mill

import scala.annotation.nowarn

package object kotlinlib {
  implicit class DepSyntax(ctx: StringContext) {
    @deprecated("Use mvn \"<dep>\" instead.", "Mill 0.12.17")
    def ivy(args: Any*): Dep = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }

    // Forward-compatibility with Mill 1.0
    def mvn(args: Any*): Dep = ivy(args: _*): @nowarn("cat=deprecation")
  }

  type Dep = mill.scalalib.Dep
  val Dep = mill.scalalib.Dep

  type TestModule = mill.scalalib.TestModule
  val TestModule = mill.scalalib.TestModule

  type PublishModule = mill.scalalib.PublishModule
  val PublishModule = mill.scalalib.PublishModule

  type NativeImageModule = mill.scalalib.NativeImageModule

  type ZincWorkerModule = mill.scalalib.ZincWorkerModule
  val ZincWorkerModule = mill.scalalib.ZincWorkerModule

  type JvmWorkerModule = mill.scalalib.ZincWorkerModule
  val JvmWorkerModule = mill.scalalib.ZincWorkerModule

}
