package mill

package object scalalib extends mill.scalalib.JsonFormatters {
  implicit class DepSyntax(ctx: StringContext) {
    def ivy(args: Any*): Dep = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }
  }

  val CrossVersion = mill.api.CrossVersion
  type CrossVersion = mill.api.CrossVersion
}
