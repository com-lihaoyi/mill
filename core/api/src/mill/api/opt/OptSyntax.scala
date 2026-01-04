package mill.api.opt

import scala.language.implicitConversions

implicit class OptSyntax(ctx: StringContext) extends AnyVal {
  def opt(opts: Any*): Opt = {
    val vals = ctx.parts.take(opts.length).zip(opts).flatMap { case (p, a) => Seq(p, a) } ++
      ctx.parts.drop(opts.length)

    val elems: Seq[(String | os.Path)] = vals.flatMap {
      case path: os.Path => Seq(path)
      case s => Seq(s.toString).filter(_.nonEmpty)
    }
    Opt(elems*)
  }
}
