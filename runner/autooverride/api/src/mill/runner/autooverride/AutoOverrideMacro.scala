package mill.runner.autooverride

import scala.quoted.*
import mill.api.Task

object AutoOverrideMacro {
  /**
   * Macro that resolves LiteralImplicit[T] during macro expansion and passes it to Task.Stub[T]
   */
  def stubImpl[T: Type](using Quotes): Expr[mill.api.Task.Simple[T]] = {
    import quotes.reflect.*

    // Summon the implicit LiteralImplicit[T]
    Expr.summon[Task.LiteralImplicit[T]] match {
      case Some(lit) =>
        // Call Task.Stub[T]() with the summoned implicit
        '{ Task.Stub[T]()(using null.asInstanceOf[T])(using ${lit}) }
      case None =>
        report.errorAndAbort(s"Could not find implicit LiteralImplicit[${Type.show[T]}]")
    }
  }
}
