package mill

package object scalalib extends mill.scalalib.JsonFormatters {

  implicit class DepSyntax(ctx: StringContext) extends AnyVal {
    def mvn(args: Any*): Dep = Dep.parse {
      (
        ctx.parts.take(args.length).zip(args).flatMap { case (p, a) => Seq(p, a) } ++
          ctx.parts.drop(args.length)
      ).mkString
    }

    @deprecated("Use `mvn` string interpolator instead.", "Mill 0.12.11")
    def ivy(args: Any*): Dep = mvn(args*)
  }

  val CrossVersion = mill.define.CrossVersion
  type CrossVersion = mill.define.CrossVersion

}
