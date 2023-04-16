package mill

package object scalalib extends mill.scalalib.JsonFormatters {
  implicit class DepSyntax(ctx: StringContext) {
    def ivy(args: Any*) = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }
  }
}
