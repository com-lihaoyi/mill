package mill.api.internal

import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable.VectorMap
import scala.util.DynamicVariable
import scala.quoted.*

trait Cacher extends mill.moduledefs.Cacher {
  // Lock-free, first-writer-wins cache of `Task` definitions per enclosing
  // source position. We deliberately do NOT hold a monitor across the
  // user-supplied body `t`: the body routinely calls `cachedTask` on other
  // modules, so holding `this.synchronized` across it caused AB-BA deadlocks
  // when multiple threads (e.g. concurrent BSP requests) traversed the
  // module graph from different starting points. See the discussion around
  // the BSP `resolve _` removal for the failure mode.
  //
  // Two threads racing on the same key both compute `t` (cheap pure `Task`
  // construction); `putIfAbsent` ensures the *first* writer's value is the
  // one returned to every subsequent reader, including this thread, so the
  // observed value is stable and never reassigned. Losers' transient
  // duplicates become unreachable immediately.
  private lazy val cacherLazyMap =
    new ConcurrentHashMap[sourcecode.Enclosing, AnyRef]()

  protected def cachedTask[T](t: => T)(using c: sourcecode.Enclosing): T = {
    if (Cacher.taskEvaluationStack.value.contains((c, this))) {
      sys.error(
        "Circular task dependency detected:\n" +

          (Cacher.taskEvaluationStack.value.keys ++ Seq((c, this)))
            .map { case (c, o) =>
              val taskName = c.value.split("\\.|#| ").filter(!_.startsWith("$anon")).last
              o.toString match {
                case "" => taskName
                case s => s + "." + taskName
              }
            }
            .mkString("\ndepends on: ")
      )
    }

    val cached = cacherLazyMap.get(c)
    if (cached != null) cached.asInstanceOf[T]
    else
      Cacher.taskEvaluationStack.withValue(
        Cacher.taskEvaluationStack.value ++ Seq((c, this) -> ())
      ) {
        val computed = t.asInstanceOf[AnyRef]
        // First-writer-wins: only the first successful insert is observable
        // afterwards. If another thread beat us, return their value so the
        // cache is the single source of truth and the value seen by callers
        // never changes once set.
        val existing = cacherLazyMap.putIfAbsent(c, computed)
        (if (existing == null) computed else existing).asInstanceOf[T]
      }
  }
}

private[mill] object Cacher {
  // Use a VectorMap for fast contains checking while preserving insertion order
  private[mill] val taskEvaluationStack =
    DynamicVariable[VectorMap[(sourcecode.Enclosing, Any), Unit]](VectorMap())
  private[mill] def withMacroOwner[T](using Quotes)(op: quotes.reflect.Symbol => T): T = {
    import quotes.reflect.*
    // In Scala 3, the top level splice of a macro is owned by a symbol called "macro" with the macro flag set,
    // but not the method flag.
    def isMacroOwner(sym: Symbol): Boolean =
      sym.name == "macro" && sym.flags.is(Flags.Macro | Flags.Synthetic) && !sym.flags.is(
        Flags.Method
      )

    def loop(owner: Symbol): T =
      if owner.isPackageDef || owner == Symbol.noSymbol then
        report.errorAndAbort(
          "Cannot find the owner of the macro expansion",
          Position.ofMacroExpansion
        )
      else if isMacroOwner(owner) then op(owner.owner) // Skip the "macro" owner
      else loop(owner.owner)

    loop(Symbol.spliceOwner)
  }

  def impl0[T: Type](using Quotes)(t: Expr[T]): Expr[T] = withMacroOwner { owner =>
    import quotes.reflect.*

    val enclosingCtx = Expr.summon[sourcecode.Enclosing].getOrElse(
      report.errorAndAbort("Cannot find enclosing context", Position.ofMacroExpansion)
    )

    val thisSel = This(owner.owner).asExprOf[Cacher]
    '{ $thisSel.cachedTask[T](${ t })(using $enclosingCtx) }
  }
}
